package com.lexibridge.operations.modules.payments.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

@Component
public class PaymentSplitCalculator {

    public Map<String, BigDecimal> split(BigDecimal amount, BigDecimal merchantRatio, BigDecimal platformRatio) {
        BigDecimal totalRatio = merchantRatio.add(platformRatio);
        if (totalRatio.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Ratio must be greater than zero.");
        }

        BigDecimal merchantAmount = amount
            .multiply(merchantRatio)
            .divide(totalRatio, 2, RoundingMode.HALF_UP);
        BigDecimal platformAmount = amount.subtract(merchantAmount);

        return Map.of(
            "merchantAmount", merchantAmount,
            "platformAmount", platformAmount
        );
    }
}
