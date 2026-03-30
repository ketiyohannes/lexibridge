package com.lexibridge.operations.modules.admin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexibridge.operations.governance.AuditLogService;
import com.lexibridge.operations.modules.admin.repository.WebhookRepository;
import com.lexibridge.operations.security.privacy.FieldEncryptionService;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WebhookDeliveryService {

    private final WebhookRepository webhookRepository;
    private final WebhookSecurityService webhookSecurityService;
    private final FieldEncryptionService fieldEncryptionService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public WebhookDeliveryService(WebhookRepository webhookRepository,
                                  WebhookSecurityService webhookSecurityService,
                                  FieldEncryptionService fieldEncryptionService,
                                  AuditLogService auditLogService,
                                  ObjectMapper objectMapper) {
        this.webhookRepository = webhookRepository;
        this.webhookSecurityService = webhookSecurityService;
        this.fieldEncryptionService = fieldEncryptionService;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> dispatchToLocation(long locationId,
                                                  String eventType,
                                                  Map<String, Object> payload,
                                                  long actorUserId) {
        List<Map<String, Object>> endpoints = webhookRepository.activeWebhooksByLocation(locationId);
        int delivered = 0;
        int failed = 0;
        for (Map<String, Object> endpoint : endpoints) {
            boolean ok = deliver(endpoint, eventType, payload);
            if (ok) {
                delivered++;
            } else {
                failed++;
            }
        }
        auditLogService.logUserEvent(
            actorUserId,
            "WEBHOOK_DISPATCHED",
            "webhook_endpoint",
            "location:" + locationId,
            locationId,
            Map.of("eventType", eventType, "endpoints", endpoints.size(), "delivered", delivered, "failed", failed)
        );
        return Map.of("locationId", locationId, "eventType", eventType, "attempted", endpoints.size(), "delivered", delivered, "failed", failed);
    }

    public Map<String, Object> dispatchToWebhook(long webhookId,
                                                 String eventType,
                                                 Map<String, Object> payload,
                                                 long actorUserId) {
        Map<String, Object> endpoint = webhookRepository.activeWebhookById(webhookId)
            .orElseThrow(() -> new IllegalArgumentException("Webhook not found."));
        boolean delivered = deliver(endpoint, eventType, payload);
        Long locationId = ((Number) endpoint.get("location_id")).longValue();
        auditLogService.logUserEvent(
            actorUserId,
            "WEBHOOK_DISPATCHED",
            "webhook_endpoint",
            String.valueOf(webhookId),
            locationId,
            Map.of("eventType", eventType, "delivered", delivered)
        );
        return Map.of("webhookId", webhookId, "eventType", eventType, "delivered", delivered);
    }

    private boolean deliver(Map<String, Object> endpoint, String eventType, Map<String, Object> payload) {
        long webhookId = ((Number) endpoint.get("id")).longValue();
        String callbackUrl = String.valueOf(endpoint.get("callback_url"));
        Object cidrValue = endpoint.get("whitelisted_cidr");
        String whitelistedCidr = cidrValue == null
            ? String.valueOf(endpoint.get("whitelisted_ip")) + "/32"
            : String.valueOf(cidrValue);

        String host = URI.create(callbackUrl).getHost();
        String resolvedIp = webhookSecurityService.resolve(host);
        if (!webhookSecurityService.isPrivateIp(resolvedIp)
            || !webhookSecurityService.isIpAllowedByCidrs(resolvedIp, whitelistedCidr)) {
            webhookRepository.createDeliveryAttempt(webhookId, eventType, 1, "BLOCKED", null, "IP allowlist check failed", 0);
            return false;
        }

        byte[] encryptedSecret = (byte[]) endpoint.get("signing_secret");
        byte[] secret = fieldEncryptionService.decrypt(encryptedSecret);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        String body = toJson(payload);
        String signature = hmacSha256Hex(secret, eventType + "|" + timestamp + "|" + nonce + "|" + body);

        for (int attempt = 1; attempt <= 3; attempt++) {
            long started = System.currentTimeMillis();
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(callbackUrl))
                    .header("Content-Type", "application/json")
                    .header("X-LexiBridge-Event", eventType)
                    .header("X-LexiBridge-Timestamp", timestamp)
                    .header("X-LexiBridge-Nonce", nonce)
                    .header("X-LexiBridge-Signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                long duration = System.currentTimeMillis() - started;
                int statusCode = response.statusCode();
                String responseBody = truncate(response.body(), 500);
                if (statusCode >= 200 && statusCode < 300) {
                    webhookRepository.createDeliveryAttempt(webhookId, eventType, attempt, "DELIVERED", statusCode, responseBody, duration);
                    return true;
                }
                webhookRepository.createDeliveryAttempt(webhookId, eventType, attempt, "FAILED", statusCode, responseBody, duration);
            } catch (Exception ex) {
                long duration = System.currentTimeMillis() - started;
                webhookRepository.createDeliveryAttempt(webhookId, eventType, attempt, "FAILED", null, truncate(ex.getMessage(), 500), duration);
            }

            if (attempt < 3) {
                try {
                    Thread.sleep(100L * attempt);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? new HashMap<>() : payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not serialize webhook payload", e);
        }
    }

    private String hmacSha256Hex(byte[] secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to sign webhook payload", e);
        }
    }

    private String truncate(String value, int limit) {
        if (value == null) {
            return null;
        }
        if (value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit);
    }
}
