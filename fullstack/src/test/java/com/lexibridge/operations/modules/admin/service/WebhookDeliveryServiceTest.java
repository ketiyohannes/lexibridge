package com.lexibridge.operations.modules.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexibridge.operations.governance.AuditLogService;
import com.lexibridge.operations.modules.admin.repository.WebhookRepository;
import com.lexibridge.operations.security.privacy.FieldEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookDeliveryServiceTest {

    @Mock
    private WebhookRepository webhookRepository;
    @Mock
    private WebhookSecurityService webhookSecurityService;
    @Mock
    private FieldEncryptionService fieldEncryptionService;
    @Mock
    private AuditLogService auditLogService;

    private WebhookDeliveryService webhookDeliveryService;

    @BeforeEach
    void setUp() {
        webhookDeliveryService = new WebhookDeliveryService(
            webhookRepository,
            webhookSecurityService,
            fieldEncryptionService,
            auditLogService,
            new ObjectMapper()
        );
    }

    @Test
    void dispatchToWebhook_shouldBlockWhenIpCheckFails() {
        when(webhookRepository.activeWebhookById(7L)).thenReturn(Optional.of(Map.of(
            "id", 7L,
            "location_id", 1L,
            "callback_url", "http://private.local/hook",
            "whitelisted_ip", "10.1.1.2",
            "whitelisted_cidr", "10.1.1.0/24",
            "signing_secret", new byte[]{1}
        )));
        when(webhookSecurityService.resolve("private.local")).thenReturn("8.8.8.8");
        when(webhookSecurityService.isPrivateIp("8.8.8.8")).thenReturn(false);

        Map<String, Object> result = webhookDeliveryService.dispatchToWebhook(7L, "BOOKING_CONFIRMED", Map.of("id", 1), 9L);

        assertEquals(false, result.get("delivered"));
        verify(webhookRepository).createDeliveryAttempt(7L, "BOOKING_CONFIRMED", 1, "BLOCKED", null, "IP allowlist check failed", 0);
        verify(auditLogService).logUserEvent(anyLong(), anyString(), anyString(), anyString(), any(), any());
    }
}
