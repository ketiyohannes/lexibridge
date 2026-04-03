package com.lexibridge.operations.scheduler;

import com.lexibridge.operations.governance.RetentionService;
import com.lexibridge.operations.monitoring.OperationalAlertService;
import com.lexibridge.operations.modules.booking.service.BookingService;
import com.lexibridge.operations.modules.leave.service.LeaveService;
import com.lexibridge.operations.modules.moderation.service.ModerationService;
import com.lexibridge.operations.modules.payments.service.PaymentsService;
import com.lexibridge.operations.security.api.HmacAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class OperationalScheduler {

    private static final Logger log = LoggerFactory.getLogger(OperationalScheduler.class);

    private final BookingService bookingService;
    private final LeaveService leaveService;
    private final ModerationService moderationService;
    private final PaymentsService paymentsService;
    private final HmacAuthService hmacAuthService;
    private final OperationalAlertService operationalAlertService;
    private final RetentionService retentionService;

    public OperationalScheduler(BookingService bookingService,
                                LeaveService leaveService,
                                ModerationService moderationService,
                                PaymentsService paymentsService,
                                HmacAuthService hmacAuthService,
                                OperationalAlertService operationalAlertService,
                                RetentionService retentionService) {
        this.bookingService = bookingService;
        this.leaveService = leaveService;
        this.moderationService = moderationService;
        this.paymentsService = paymentsService;
        this.hmacAuthService = hmacAuthService;
        this.operationalAlertService = operationalAlertService;
        this.retentionService = retentionService;
    }

    @Scheduled(fixedDelay = 60_000)
    public void expireReservations() {
        int affected = bookingService.expireUnconfirmedReservations();
        if (affected > 0) {
            log.info("Expired {} stale reservations", affected);
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void autoCloseNoShows() {
        int affected = bookingService.autoCloseNoShows();
        if (affected > 0) {
            log.info("Auto-closed {} no-show orders", affected);
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void markLeaveOverdues() {
        int affected = leaveService.markOverdueApprovals();
        if (affected > 0) {
            log.info("Marked {} leave approval tasks as overdue", affected);
        }
    }

    @Scheduled(fixedDelay = 300_000)
    public void moderationSuspensionSweep() {
        int affected = moderationService.runSuspensionSweep();
        if (affected > 0) {
            log.info("Applied {} automatic moderation suspensions", affected);
        }
    }

    @Scheduled(cron = "0 15 0 * * *")
    public void runDailyReconciliation() {
        LocalDate businessDate = LocalDate.now().minusDays(1);
        for (Long locationId : paymentsService.activeLocationIds()) {
            try {
                paymentsService.runDailyReconciliation(locationId, businessDate, 1L);
                log.info("Daily reconciliation run completed for location={} businessDate={}", locationId, businessDate);
            } catch (RuntimeException ex) {
                log.error("Daily reconciliation failed for location={} businessDate={} reason={}", locationId, businessDate, ex.getMessage());
            }
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void cleanupExpiredNonces() {
        int removed = hmacAuthService.purgeExpiredNonces();
        if (removed > 0) {
            log.info("Removed {} expired nonce entries", removed);
        }
    }

    @Scheduled(fixedDelay = 300_000)
    public void evaluateOperationalAlerts() {
        operationalAlertService.evaluateAlerts();
    }

    @Scheduled(cron = "0 30 2 * * *")
    public void runRetentionPurge() {
        int redactionRemoved = retentionService.purgeExpiredAuditRedactionEvents();
        int auditRemoved = retentionService.purgeExpiredAuditLogs();
        int reconRemoved = retentionService.purgeExpiredReconciliationExceptions();
        int reconRunRemoved = retentionService.purgeExpiredReconciliationRuns();
        if (redactionRemoved > 0 || auditRemoved > 0 || reconRemoved > 0 || reconRunRemoved > 0) {
            log.info("Retention purge removed auditRedactions={}, audit={}, reconciliationExceptions={}, reconciliationRuns={}", redactionRemoved, auditRemoved, reconRemoved, reconRunRemoved);
        }
    }
}
