package com.lexibridge.operations.security.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiRateLimiterServiceTest {

    @Test
    void allow_shouldRejectWhenLimitExceededWithinWindow() {
        ApiRateLimiterService limiter = new ApiRateLimiterService();
        for (int i = 0; i < 60; i++) {
            assertTrue(limiter.allow("user:alpha"));
        }
        assertFalse(limiter.allow("user:alpha"));
    }

    @Test
    void allow_shouldTrackKeysIndependently() {
        ApiRateLimiterService limiter = new ApiRateLimiterService();
        for (int i = 0; i < 60; i++) {
            assertTrue(limiter.allow("user:alpha"));
        }
        assertFalse(limiter.allow("user:alpha"));
        assertTrue(limiter.allow("user:beta"));
    }
}
