package com.lexibridge.operations.monitoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexibridge.operations.modules.booking.service.BookingService;
import com.lexibridge.operations.modules.payments.service.PaymentsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OperationalAlertServiceTest {

    @Mock
    private PaymentsService paymentsService;
    @Mock
    private BookingService bookingService;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private OperationalAlertService service;

    @BeforeEach
    void setUp() {
        service = new OperationalAlertService(paymentsService, bookingService, jdbcTemplate, new ObjectMapper());
    }

    @Test
    void evaluateAlerts_shouldInsertAlertsOnThresholdBreach() {
        when(paymentsService.paymentFailureRateLastHour()).thenReturn(4.2);
        when(bookingService.negativeInventorySignals()).thenReturn(1);

        service.evaluateAlerts();

        verify(jdbcTemplate, atLeastOnce()).update(anyString(), any(), any(), any());
    }
}
