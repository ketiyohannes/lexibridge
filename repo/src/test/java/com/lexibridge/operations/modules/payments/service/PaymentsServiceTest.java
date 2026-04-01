package com.lexibridge.operations.modules.payments.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexibridge.operations.governance.AuditLogService;
import com.lexibridge.operations.modules.payments.repository.PaymentsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentsServiceTest {

    @Mock
    private PaymentsRepository paymentsRepository;
    @Mock
    private AuditLogService auditLogService;

    private PaymentsService paymentsService;

    @BeforeEach
    void setUp() {
        paymentsService = new PaymentsService(paymentsRepository, new PaymentSplitCalculator(), new ObjectMapper(), auditLogService);
    }

    @Test
    void createTender_shouldCreateSplitSnapshot() {
        when(paymentsRepository.createTender(anyLong(), any(), any(), any(), any(), any(), anyLong())).thenReturn(91L);
        when(paymentsRepository.bookingLocationId(10L)).thenReturn(1L);
        when(paymentsRepository.splitPolicyForLocation(1L)).thenReturn(Map.of(
            "merchantRatio", BigDecimal.valueOf(75),
            "platformRatio", BigDecimal.valueOf(25)
        ));

        Map<String, Object> result = paymentsService.createTender(
            10L,
            "CASH",
            BigDecimal.valueOf(100),
            "USD",
            null,
            null,
            1L
        );

        assertEquals(91L, result.get("tenderEntryId"));
        verify(paymentsRepository).snapshotSplit(eq(91L), any(), any(), eq(BigDecimal.valueOf(75.00).setScale(2)), eq(BigDecimal.valueOf(25.00).setScale(2)));
    }

    @Test
    void processCallback_shouldReturnDuplicateWhenAlreadyProcessed() {
        when(paymentsRepository.registerCallback("T1", "X1", "{\"ok\":true}")).thenReturn(false);
        when(paymentsRepository.confirmTenderByTerminalTxn("T1", "X1")).thenReturn(1);

        Map<String, Object> response = paymentsService.processCallback("T1", "X1", Map.of("ok", true));

        assertEquals("DUPLICATE", response.get("status"));
    }

    @Test
    void requestRefund_shouldRequireSupervisorAboveTwoHundred() {
        when(paymentsRepository.tenderCreatedAt(99L)).thenReturn(LocalDateTime.now().minusDays(3));
        when(paymentsRepository.tenderAmountForUpdate(99L)).thenReturn(BigDecimal.valueOf(500));
        when(paymentsRepository.approvedRefundTotalForUpdate(99L)).thenReturn(BigDecimal.ZERO);
        when(paymentsRepository.createRefund(anyLong(), any(), any(), any(), eq(true), anyLong())).thenReturn(500L);

        Map<String, Object> result = paymentsService.requestRefund(
            99L,
            BigDecimal.valueOf(250),
            "USD",
            "overcharge",
            3L
        );

        assertEquals(true, result.get("requiresSupervisor"));
    }

    @Test
    void requestRefund_shouldRejectWhenAmountExceedsRemainingBalance() {
        when(paymentsRepository.tenderCreatedAt(88L)).thenReturn(LocalDateTime.now().minusDays(2));
        when(paymentsRepository.tenderAmountForUpdate(88L)).thenReturn(BigDecimal.valueOf(100));
        when(paymentsRepository.approvedRefundTotalForUpdate(88L)).thenReturn(BigDecimal.valueOf(95));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> paymentsService.requestRefund(
            88L,
            BigDecimal.valueOf(10),
            "USD",
            "duplicate charge",
            7L
        ));

        assertTrue(ex.getMessage().contains("remaining refundable balance"));
    }

    @Test
    void requestRefund_shouldRejectBeyondThirtyDayWindow() {
        when(paymentsRepository.tenderCreatedAt(99L)).thenReturn(LocalDateTime.now().minusDays(31));

        assertThrows(IllegalArgumentException.class, () -> paymentsService.requestRefund(
            99L,
            BigDecimal.valueOf(10),
            "USD",
            "late",
            3L
        ));
    }

    @Test
    void runDailyReconciliation_shouldOpenExceptions() {
        when(paymentsRepository.countMissingTenderForBusinessDate(1L, LocalDate.of(2026, 1, 1))).thenReturn(1);
        when(paymentsRepository.countDuplicateCallbackSignals()).thenReturn(2);
        when(paymentsRepository.countMismatchForBusinessDate(1L, LocalDate.of(2026, 1, 1))).thenReturn(0);
        when(paymentsRepository.createReconciliationRun(eq(1L), eq(LocalDate.of(2026, 1, 1)), eq(1L), any())).thenReturn(42L);

        Map<String, Object> result = paymentsService.runDailyReconciliation(1L, LocalDate.of(2026, 1, 1), 1L);

        assertEquals(42L, result.get("runId"));
        verify(paymentsRepository).createException(eq(42L), eq("MISSING_TENDER"), any());
        verify(paymentsRepository).createException(eq(42L), eq("DUPLICATE_CALLBACK"), any());
    }

    @Test
    void exceptionWorkflow_shouldForwardStatusUpdates() {
        when(paymentsRepository.reconciliationExceptions("OPEN")).thenReturn(List.of(Map.of("id", 1L, "status", "OPEN")));

        assertEquals(1, paymentsService.exceptions("OPEN").size());
        paymentsService.markExceptionInReview(1L, 9L, "investigating");
        paymentsService.resolveException(1L, 9L, "fixed");
        paymentsService.reopenException(1L, 9L, "recheck");

        verify(paymentsRepository).updateExceptionStatus(1L, "IN_REVIEW", "investigating", 9L);
        verify(paymentsRepository).updateExceptionStatus(1L, "RESOLVED", "fixed", 9L);
        verify(paymentsRepository).updateExceptionStatus(1L, "REOPENED", "recheck", 9L);
    }

    @Test
    void exportReconciliationRun_shouldReturnJsonPayload() {
        when(paymentsRepository.reconciliationRunById(42L)).thenReturn(Map.of(
            "id", 42L,
            "location_id", 3L,
            "business_date", "2026-01-02",
            "status", "COMPLETED"
        ));
        when(paymentsRepository.reconciliationExceptionsByRunId(42L)).thenReturn(List.of(
            Map.of("id", 10L, "exception_type", "MISSING_TENDER", "status", "OPEN")
        ));

        String payload = new String(paymentsService.exportReconciliationRun(42L, "json"), java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(payload.contains("\"run\""));
        assertTrue(payload.contains("\"exceptions\""));
        assertTrue(payload.contains("MISSING_TENDER"));
    }
}
