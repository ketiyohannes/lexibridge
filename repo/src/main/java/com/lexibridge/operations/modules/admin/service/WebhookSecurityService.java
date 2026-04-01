package com.lexibridge.operations.modules.admin.service;

import com.lexibridge.operations.modules.admin.repository.WebhookRepository;
import com.lexibridge.operations.security.privacy.FieldEncryptionService;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class WebhookSecurityService {

    private final WebhookRepository webhookRepository;
    private final FieldEncryptionService fieldEncryptionService;
    private final SecureRandom secureRandom = new SecureRandom();

    public WebhookSecurityService(WebhookRepository webhookRepository,
                                  FieldEncryptionService fieldEncryptionService) {
        this.webhookRepository = webhookRepository;
        this.fieldEncryptionService = fieldEncryptionService;
    }

    public long register(long locationId, String name, String callbackUrl) {
        return register(locationId, name, callbackUrl, null);
    }

    public long register(long locationId, String name, String callbackUrl, String allowedCidr) {
        String host = URI.create(callbackUrl).getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Invalid callback URL host");
        }

        String resolvedIp = resolve(host);
        if (!isPrivateIp(resolvedIp)) {
            throw new IllegalArgumentException("Webhook callback must resolve to private IP range");
        }

        String cidr = (allowedCidr == null || allowedCidr.isBlank())
            ? inferPrivateRangeCidr(resolvedIp)
            : allowedCidr.trim();
        if (!isPrivateCidr(cidr) || !cidrContains(cidr, resolvedIp)) {
            throw new IllegalArgumentException("Webhook allowlist must be a private CIDR range that includes the resolved callback IP");
        }

        byte[] secret = new byte[32];
        secureRandom.nextBytes(secret);
        return webhookRepository.create(locationId, name, callbackUrl, resolvedIp, cidr, fieldEncryptionService.encrypt(secret));
    }

    public boolean canDeliver(long webhookId) {
        Map<String, Object> policy = webhookRepository.findDeliveryPolicy(webhookId)
            .orElseThrow(() -> new IllegalArgumentException("Webhook not found"));
        String url = String.valueOf(policy.get("callback_url"));
        String whitelistedCidr = String.valueOf(policy.get("whitelisted_cidr"));
        String host = URI.create(url).getHost();
        if (host == null) {
            return false;
        }
        String resolvedIp = resolve(host);
        return isPrivateIp(resolvedIp) && isIpAllowedByCidrs(resolvedIp, whitelistedCidr);
    }

    String resolve(String host) {
        try {
            return InetAddress.getByName(host).getHostAddress();
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to resolve host", e);
        }
    }

    boolean isPrivateIp(String ip) {
        if (ip.startsWith("10.")) {
            return true;
        }
        if (ip.startsWith("192.168.")) {
            return true;
        }
        if (ip.startsWith("172.")) {
            String[] parts = ip.split("\\.");
            if (parts.length > 1) {
                int second = Integer.parseInt(parts[1]);
                return second >= 16 && second <= 31;
            }
        }
        return ip.equals("127.0.0.1") || ip.equals("::1");
    }

    boolean isPrivateCidr(String cidr) {
        String[] parts = cidr.split("/");
        if (parts.length != 2) {
            return false;
        }
        String baseIp = parts[0].trim();
        int prefix;
        try {
            prefix = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException ex) {
            return false;
        }
        return prefix >= 0 && prefix <= 32 && isPrivateIp(baseIp);
    }

    boolean cidrContains(String cidr, String ip) {
        String[] parts = cidr.split("/");
        if (parts.length != 2) {
            return false;
        }
        long ipValue = toIpv4Long(ip);
        long network = toIpv4Long(parts[0]);
        int prefix = Integer.parseInt(parts[1]);
        long mask = prefix == 0 ? 0L : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        return (ipValue & mask) == (network & mask);
    }

    boolean isIpAllowedByCidrs(String ip, String cidrs) {
        if (cidrs == null || cidrs.isBlank()) {
            return false;
        }
        for (String cidr : cidrs.split(",")) {
            String trimmed = cidr.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (isPrivateCidr(trimmed) && cidrContains(trimmed, ip)) {
                return true;
            }
        }
        return false;
    }

    private String inferPrivateRangeCidr(String ip) {
        if (ip.startsWith("10.")) {
            return "10.0.0.0/8";
        }
        if (ip.startsWith("192.168.")) {
            return "192.168.0.0/16";
        }
        if (ip.startsWith("172.")) {
            return "172.16.0.0/12";
        }
        if ("127.0.0.1".equals(ip)) {
            return "127.0.0.1/32";
        }
        throw new IllegalArgumentException("Unsupported private IP for CIDR inference");
    }

    private long toIpv4Long(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            throw new IllegalArgumentException("Only IPv4 CIDR is supported");
        }
        long value = 0;
        for (String part : parts) {
            int octet = Integer.parseInt(part);
            if (octet < 0 || octet > 255) {
                throw new IllegalArgumentException("Invalid IPv4 address");
            }
            value = (value << 8) + octet;
        }
        return value;
    }

    public String generateNonce() {
        byte[] nonce = new byte[12];
        secureRandom.nextBytes(nonce);
        return HexFormat.of().formatHex(nonce);
    }

    public List<Map<String, Object>> activeWebhooks() {
        return webhookRepository.activeWebhooks();
    }
}
