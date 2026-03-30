package com.lexibridge.operations.modules.admin.service;

import com.lexibridge.operations.modules.admin.repository.WebhookRepository;
import com.lexibridge.operations.security.privacy.FieldEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookSecurityServiceTest {

    @Mock
    private WebhookRepository webhookRepository;
    @Mock
    private FieldEncryptionService fieldEncryptionService;

    private WebhookSecurityService service;

    @BeforeEach
    void setUp() {
        service = new WebhookSecurityService(webhookRepository, fieldEncryptionService) {
            @Override
            String resolve(String host) {
                return switch (host) {
                    case "private.local" -> "10.10.1.2";
                    case "public.example" -> "8.8.8.8";
                    default -> "127.0.0.1";
                };
            }
        };
    }

    @Test
    void register_shouldRejectPublicIpWebhook() {
        assertThrows(IllegalArgumentException.class,
            () -> service.register(1L, "bad", "http://public.example/hook"));
    }

    @Test
    void register_shouldAcceptPrivateIpWebhook() {
        when(fieldEncryptionService.encrypt(any())).thenAnswer(inv -> inv.getArgument(0));
        when(webhookRepository.create(anyLong(), anyString(), anyString(), anyString(), anyString(), any())).thenReturn(10L);
        long id = service.register(1L, "ok", "http://private.local/hook");
        assertEquals(10L, id);
    }

    @Test
    void register_shouldRejectCidrOutsideResolvedDestination() {
        assertThrows(IllegalArgumentException.class,
            () -> service.register(1L, "ok", "http://private.local/hook", "192.168.0.0/16"));
    }

    @Test
    void canDeliver_shouldRevalidatePrivateIp() {
        when(webhookRepository.findDeliveryPolicy(9L)).thenReturn(Optional.of(java.util.Map.of(
            "callback_url", "http://private.local/evt",
            "whitelisted_cidr", "10.0.0.0/8"
        )));
        assertTrue(service.canDeliver(9L));
    }

    @Test
    void privateRangeHelper_shouldHandleRfc1918() {
        assertTrue(service.isPrivateIp("192.168.10.10"));
        assertTrue(service.isPrivateIp("172.16.10.10"));
        assertFalse(service.isPrivateIp("172.40.10.10"));
    }

    @Test
    void cidrMatching_shouldAllowSubnetRange() {
        assertTrue(service.isIpAllowedByCidrs("10.10.1.2", "10.10.0.0/16"));
        assertFalse(service.isIpAllowedByCidrs("10.10.1.2", "10.11.0.0/16"));
    }
}
