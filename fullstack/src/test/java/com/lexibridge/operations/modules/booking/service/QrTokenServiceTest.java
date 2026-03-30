package com.lexibridge.operations.modules.booking.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QrTokenServiceTest {

    @Test
    void createAndValidate_shouldRoundTripBookingId() {
        QrTokenService tokenService = new QrTokenService("unit-test-secret");
        String token = tokenService.createToken(123L, LocalDateTime.now().plusMinutes(5));
        long bookingId = tokenService.validateAndExtractBookingId(token);
        assertEquals(123L, bookingId);
    }

    @Test
    void validate_shouldRejectExpiredToken() {
        QrTokenService tokenService = new QrTokenService("unit-test-secret");
        String token = tokenService.createToken(123L, LocalDateTime.now().minusSeconds(1));
        assertThrows(IllegalArgumentException.class, () -> tokenService.validateAndExtractBookingId(token));
    }
}
