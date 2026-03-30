package com.lexibridge.operations.monitoring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexibridge.operations.modules.booking.service.BookingService;
import com.lexibridge.operations.modules.payments.service.PaymentsService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class OperationalAlertService {

    private final PaymentsService paymentsService;
    private final BookingService bookingService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OperationalAlertService(PaymentsService paymentsService,
                                   BookingService bookingService,
                                   JdbcTemplate jdbcTemplate,
                                   ObjectMapper objectMapper) {
        this.paymentsService = paymentsService;
        this.bookingService = bookingService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void evaluateAlerts() {
        double failureRate = paymentsService.paymentFailureRateLastHour();
        if (failureRate > 3.0) {
            insertAlert("PAYMENT_FAILURE_RATE_HIGH", "HIGH", Map.of("failureRatePercent", failureRate));
        }

        int negativeInventory = bookingService.negativeInventorySignals();
        if (negativeInventory > 0) {
            insertAlert("NEGATIVE_SLOT_INVENTORY", "CRITICAL", Map.of("negativeInventorySlots", negativeInventory));
        }
    }

    private void insertAlert(String alertCode, String severity, Map<String, Object> details) {
        jdbcTemplate.update(
            """
            insert into alert_event (alert_code, severity, status, details_json, started_at)
            values (?, ?, 'OPEN', cast(? as json), current_timestamp)
            """,
            alertCode,
            severity,
            toJson(details)
        );
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to serialize alert details", e);
        }
    }
}
