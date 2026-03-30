package com.lexibridge.operations.security.privacy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PiiMaskingServiceTest {

    private final PiiMaskingService service = new PiiMaskingService();

    @Test
    void maskPhone_shouldRevealOnlyLastFourDigits() {
        assertEquals("***-***-1234", service.maskPhone("+1 (555) 000-1234"));
    }

    @Test
    void maskName_shouldShowOnlyOuterCharacters() {
        assertEquals("A***e", service.maskName("Alice"));
    }

    @Test
    void maskEmail_shouldHideLocalAndDomainCore() {
        assertEquals("a***e@e***e.com", service.maskEmail("alice@example.com"));
    }
}
