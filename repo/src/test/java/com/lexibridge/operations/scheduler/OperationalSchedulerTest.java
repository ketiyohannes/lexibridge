package com.lexibridge.operations.scheduler;

import com.lexibridge.operations.governance.RetentionService;
import com.lexibridge.operations.monitoring.OperationalAlertService;
import com.lexibridge.operations.modules.booking.service.BookingService;
import com.lexibridge.operations.modules.leave.service.LeaveService;
import com.lexibridge.operations.modules.moderation.service.ModerationService;
import com.lexibridge.operations.modules.payments.service.PaymentsService;
import com.lexibridge.operations.security.api.HmacAuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OperationalSchedulerTest {

    @Mock
    private BookingService bookingService;
    @Mock
    private LeaveService leaveService;
    @Mock
    private ModerationService moderationService;
    @Mock
    private PaymentsService paymentsService;
    @Mock
    private HmacAuthService hmacAuthService;
    @Mock
    private OperationalAlertService operationalAlertService;
    @Mock
    private RetentionService retentionService;

    @Test
    void scheduledMethods_shouldDelegateToServices() {
        OperationalScheduler scheduler = new OperationalScheduler(
            bookingService,
            leaveService,
            moderationService,
            paymentsService,
            hmacAuthService,
            operationalAlertService,
            retentionService
        );

        scheduler.expireReservations();
        scheduler.autoCloseNoShows();
        scheduler.markLeaveOverdues();
        scheduler.moderationSuspensionSweep();
        scheduler.cleanupExpiredNonces();
        when(paymentsService.activeLocationIds()).thenReturn(java.util.List.of(1L, 2L));
        scheduler.runDailyReconciliation();
        scheduler.evaluateOperationalAlerts();
        scheduler.runRetentionPurge();

        verify(bookingService).expireUnconfirmedReservations();
        verify(bookingService).autoCloseNoShows();
        verify(leaveService).markOverdueApprovals();
        verify(moderationService).runSuspensionSweep();
        verify(hmacAuthService).purgeExpiredNonces();
        verify(paymentsService).activeLocationIds();
        verify(paymentsService).runDailyReconciliation(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(1L));
        verify(paymentsService).runDailyReconciliation(org.mockito.ArgumentMatchers.eq(2L), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(1L));
        verify(operationalAlertService).evaluateAlerts();
        verify(retentionService).purgeExpiredAuditRedactionEvents();
        verify(retentionService).purgeExpiredAuditLogs();
        verify(retentionService).purgeExpiredReconciliationExceptions();
        verify(retentionService).purgeExpiredReconciliationRuns();
    }
}
