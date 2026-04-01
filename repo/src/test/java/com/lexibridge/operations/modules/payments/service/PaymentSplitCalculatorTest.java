package com.lexibridge.operations.modules.payments.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaymentSplitCalculatorTest {

    private final PaymentSplitCalculator calculator = new PaymentSplitCalculator();

    @Test
    void split_shouldApplyDefault8020() {
        Map<String, BigDecimal> split = calculator.split(
            BigDecimal.valueOf(100.00),
            BigDecimal.valueOf(80),
            BigDecimal.valueOf(20)
        );

        assertEquals(BigDecimal.valueOf(80.00).setScale(2), split.get("merchantAmount"));
        assertEquals(BigDecimal.valueOf(20.00).setScale(2), split.get("platformAmount"));
    }

    @Test
    void split_shouldRoundHalfUp() {
        Map<String, BigDecimal> split = calculator.split(
            BigDecimal.valueOf(10.01),
            BigDecimal.valueOf(80),
            BigDecimal.valueOf(20)
        );

        assertEquals(BigDecimal.valueOf(8.01).setScale(2), split.get("merchantAmount"));
        assertEquals(BigDecimal.valueOf(2.00).setScale(2), split.get("platformAmount"));
    }
}
