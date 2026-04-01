package com.lexibridge.operations.modules.payments.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexibridge.operations.governance.AuditLogService;
import com.lexibridge.operations.modules.payments.repository.PaymentsRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PaymentsService {

    private static final Logger log = LoggerFactory.getLogger(PaymentsService.class);
    private static final Set<String> ALLOWED_TENDER_TYPES = Set.of("CASH", "CHECK", "CARD_PRESENT");

    private final PaymentsRepository paymentsRepository;
    private final PaymentSplitCalculator splitCalculator;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;

    public PaymentsService(PaymentsRepository paymentsRepository,
                           PaymentSplitCalculator splitCalculator,
                           ObjectMapper objectMapper,
                           AuditLogService auditLogService) {
        this.paymentsRepository = paymentsRepository;
        this.splitCalculator = splitCalculator;
        this.objectMapper = objectMapper;
        this.auditLogService = auditLogService;
    }

    public Map<String, Object> dashboardSummary() {
        return paymentsRepository.summary(null);
    }

    public Map<String, Object> dashboardSummary(Long locationId) {
        return paymentsRepository.summary(locationId);
    }

    @Transactional
    public Map<String, Object> createTender(long bookingOrderId,
                                             String tenderType,
                                            BigDecimal amount,
                                            String currency,
                                            String terminalId,
                                             String terminalTxnId,
                                             long createdBy) {
        String canonicalTenderType = normalizeTenderType(tenderType);
        long tenderId = paymentsRepository.createTender(
            bookingOrderId,
            canonicalTenderType,
            amount,
            currency,
            terminalId,
            terminalTxnId,
            createdBy
        );

        Long locationId = paymentsRepository.bookingLocationId(bookingOrderId);
        if (locationId == null) {
            log.warn("Tender create failed: bookingOrderId {} not found", bookingOrderId);
            throw new IllegalArgumentException("Booking order not found for tender creation.");
        }
        Map<String, BigDecimal> policy = paymentsRepository.splitPolicyForLocation(locationId);
        BigDecimal merchantRatio = policy.get("merchantRatio");
        BigDecimal platformRatio = policy.get("platformRatio");
        Map<String, BigDecimal> split = splitCalculator.split(amount, merchantRatio, platformRatio);

        paymentsRepository.snapshotSplit(
            tenderId,
            merchantRatio,
            platformRatio,
            split.get("merchantAmount"),
            split.get("platformAmount")
        );

        auditLogService.logUserEvent(createdBy, "TENDER_CREATED", "tender_entry", String.valueOf(tenderId), null, Map.of("amount", amount));
        log.info("Payment tender created id={} bookingOrderId={} amount={} {}", tenderId, bookingOrderId, amount, currency);

        return Map.of("tenderEntryId", tenderId, "status", "PENDING");
    }

    @Transactional
    public Map<String, Object> processCallback(String terminalId,
                                               String terminalTxnId,
                                               Map<String, Object> payload,
                                               Long scopedLocationId,
                                               String actorKeyOrUser) {
        if (scopedLocationId != null) {
            Long callbackLocation = paymentsRepository.callbackTenderLocation(terminalId, terminalTxnId);
            if (callbackLocation != null && !scopedLocationId.equals(callbackLocation)) {
                auditLogService.logSystemEvent(
                    "PAYMENT_CALLBACK_DENIED",
                    "terminal_callback",
                    terminalId + ":" + terminalTxnId,
                    scopedLocationId,
                    Map.of("reason", "LOCATION_SCOPE_VIOLATION", "actor", actorKeyOrUser, "callbackLocation", callbackLocation)
                );
                log.warn("Payment callback denied terminalId={} terminalTxnId={} actor={} scopedLocation={} callbackLocation={}", terminalId, terminalTxnId, actorKeyOrUser, scopedLocationId, callbackLocation);
                throw new org.springframework.security.access.AccessDeniedException("Callback is outside the caller location scope.");
            }
        }

        boolean firstDelivery = paymentsRepository.registerCallback(terminalId, terminalTxnId, toJson(payload));
        int updated = scopedLocationId == null
            ? paymentsRepository.confirmTenderByTerminalTxn(terminalId, terminalTxnId)
            : paymentsRepository.confirmTenderByTerminalTxnInLocation(terminalId, terminalTxnId, scopedLocationId);
        auditLogService.logSystemEvent("PAYMENT_CALLBACK", "terminal_callback", terminalId + ":" + terminalTxnId, null, Map.of("firstDelivery", firstDelivery, "updated", updated));
        log.info("Payment callback processed terminalId={} terminalTxnId={} firstDelivery={} matched={}", terminalId, terminalTxnId, firstDelivery, updated);
        return Map.of(
            "firstDelivery", firstDelivery,
            "matchedTenderEntries", updated,
            "status", firstDelivery ? "PROCESSED" : "DUPLICATE"
        );
    }

    public Map<String, Object> processCallback(String terminalId, String terminalTxnId, Map<String, Object> payload) {
        return processCallback(terminalId, terminalTxnId, payload, null, "system");
    }

    @Transactional
    public Map<String, Object> requestRefund(long tenderEntryId,
                                             BigDecimal amount,
                                             String currency,
                                             String reason,
                                             long actorUserId) {
        boolean requiresSupervisor = amount.compareTo(BigDecimal.valueOf(200.00)) > 0;
        LocalDateTime createdAt = paymentsRepository.tenderCreatedAt(tenderEntryId);
        if (createdAt == null) {
            log.warn("Refund request failed: tenderEntryId {} not found", tenderEntryId);
            throw new IllegalArgumentException("Tender entry not found.");
        }
        if (createdAt.isBefore(LocalDateTime.now().minusDays(30))) {
            log.warn("Refund request denied: tenderEntryId {} outside 30 day window", tenderEntryId);
            throw new IllegalArgumentException("Refund window exceeded: refunds are allowed only within 30 days of tender creation.");
        }

        BigDecimal tenderAmount = paymentsRepository.tenderAmountForUpdate(tenderEntryId);
        if (tenderAmount == null) {
            throw new IllegalArgumentException("Tender entry not found.");
        }
        BigDecimal approvedTotal = paymentsRepository.approvedRefundTotalForUpdate(tenderEntryId);
        BigDecimal remaining = tenderAmount.subtract(approvedTotal);
        if (amount.compareTo(remaining) > 0) {
            log.warn("Refund request denied: tenderEntryId {} requested={} remaining={}", tenderEntryId, amount, remaining);
            throw new IllegalArgumentException("Refund amount exceeds remaining refundable balance.");
        }

        long refundId = paymentsRepository.createRefund(
            tenderEntryId,
            amount,
            currency,
            reason,
            requiresSupervisor,
            actorUserId
        );
        auditLogService.logUserEvent(actorUserId, "REFUND_REQUESTED", "refund_request", String.valueOf(refundId), null, Map.of("amount", amount, "requiresSupervisor", requiresSupervisor));
        log.info("Refund requested id={} tenderEntryId={} amount={} requiresSupervisor={}", refundId, tenderEntryId, amount, requiresSupervisor);
        return Map.of("refundId", refundId, "requiresSupervisor", requiresSupervisor);
    }

    @Transactional
    public void approveRefund(long refundId, long supervisorUserId) {
        paymentsRepository.approveRefund(refundId, supervisorUserId);
        auditLogService.logUserEvent(supervisorUserId, "REFUND_APPROVED", "refund_request", String.valueOf(refundId), null, Map.of());
        log.info("Refund approved refundId={} supervisorUserId={}", refundId, supervisorUserId);
    }

    @Transactional
    public Map<String, Object> runDailyReconciliation(long locationId, LocalDate businessDate, long actorUserId) {
        int missingTender = safeCount(paymentsRepository.countMissingTenderForBusinessDate(locationId, businessDate));
        int duplicateCallback = safeCount(paymentsRepository.countDuplicateCallbackSignals());
        int mismatch = safeCount(paymentsRepository.countMismatchForBusinessDate(locationId, businessDate));

        Map<String, Object> summary = new HashMap<>();
        summary.put("missingTender", missingTender);
        summary.put("duplicateCallback", duplicateCallback);
        summary.put("mismatch", mismatch);

        long runId = paymentsRepository.createReconciliationRun(locationId, businessDate, actorUserId, toJson(summary));

        List<String> opened = new ArrayList<>();
        if (missingTender > 0) {
            paymentsRepository.createException(runId, "MISSING_TENDER", toJson(Map.of("count", missingTender)));
            opened.add("MISSING_TENDER");
        }
        if (duplicateCallback > 0) {
            paymentsRepository.createException(runId, "DUPLICATE_CALLBACK", toJson(Map.of("count", duplicateCallback)));
            opened.add("DUPLICATE_CALLBACK");
        }
        if (mismatch > 0) {
            paymentsRepository.createException(runId, "MISMATCH", toJson(Map.of("count", mismatch)));
            opened.add("MISMATCH");
        }

        auditLogService.logUserEvent(actorUserId, "RECONCILIATION_RUN", "reconciliation_run", String.valueOf(runId), locationId, Map.of("exceptions", opened.size()));
        log.info("Reconciliation run id={} locationId={} businessDate={} exceptions={}", runId, locationId, businessDate, opened.size());

        return Map.of("runId", runId, "openedExceptionTypes", opened, "summary", summary);
    }

    public List<Map<String, Object>> exceptions(String status) {
        return paymentsRepository.reconciliationExceptions(status);
    }

    public List<Map<String, Object>> exceptions(long locationId, String status) {
        return paymentsRepository.reconciliationExceptionsByLocation(locationId, status);
    }

    public List<Map<String, Object>> recentTenders(int limit) {
        return paymentsRepository.recentTenders(limit);
    }

    public List<Map<String, Object>> recentTenders(long locationId, int limit) {
        return paymentsRepository.recentTendersByLocation(locationId, limit);
    }

    public List<Map<String, Object>> recentRefunds(int limit) {
        return paymentsRepository.recentRefunds(limit);
    }

    public List<Map<String, Object>> recentRefunds(long locationId, int limit) {
        return paymentsRepository.recentRefundsByLocation(locationId, limit);
    }

    public List<Map<String, Object>> recentReconciliationRuns(int limit) {
        return paymentsRepository.recentReconciliationRuns(limit);
    }

    public List<Map<String, Object>> recentReconciliationRuns(long locationId, int limit) {
        return paymentsRepository.recentReconciliationRunsByLocation(locationId, limit);
    }

    public byte[] exportReconciliationRun(long runId, String format) {
        Map<String, Object> run = paymentsRepository.reconciliationRunById(runId);
        if (run == null) {
            throw new IllegalArgumentException("Reconciliation run not found.");
        }
        List<Map<String, Object>> exceptions = paymentsRepository.reconciliationExceptionsByRunId(runId);
        String normalized = format == null ? "csv" : format.toLowerCase();
        if ("json".equals(normalized)) {
            return toJson(Map.of("run", run, "exceptions", exceptions)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        StringBuilder csv = new StringBuilder();
        csv.append("run_id,location_id,business_date,status,exception_id,exception_type,exception_status,resolution_note\n");
        if (exceptions.isEmpty()) {
            csv.append(run.get("id")).append(',')
                .append(run.get("location_id")).append(',')
                .append(run.get("business_date")).append(',')
                .append(run.get("status")).append(",,,,")
                .append('\n');
        } else {
            for (Map<String, Object> exception : exceptions) {
                csv.append(run.get("id")).append(',')
                    .append(run.get("location_id")).append(',')
                    .append(run.get("business_date")).append(',')
                    .append(run.get("status")).append(',')
                    .append(exception.get("id")).append(',')
                    .append(csvCell(exception.get("exception_type"))).append(',')
                    .append(csvCell(exception.get("status"))).append(',')
                    .append(csvCell(exception.get("resolution_note")))
                    .append('\n');
            }
        }
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Transactional
    public Map<String, Object> configureSplitPolicy(long locationId,
                                                    BigDecimal merchantRatio,
                                                    BigDecimal platformRatio,
                                                    long actorUserId) {
        if (merchantRatio == null || platformRatio == null) {
            throw new IllegalArgumentException("Both merchant and platform ratios are required.");
        }
        if (merchantRatio.compareTo(BigDecimal.ZERO) <= 0 || platformRatio.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Split ratios must be positive.");
        }
        if (merchantRatio.add(platformRatio).compareTo(BigDecimal.valueOf(100)) != 0) {
            throw new IllegalArgumentException("Split ratios must total exactly 100.");
        }
        paymentsRepository.upsertSplitPolicy(locationId, merchantRatio, platformRatio, actorUserId);
        auditLogService.logUserEvent(actorUserId, "PAYMENT_SPLIT_POLICY_UPDATED", "payment_split_policy", String.valueOf(locationId), locationId, Map.of("merchantRatio", merchantRatio, "platformRatio", platformRatio));
        return Map.of("locationId", locationId, "merchantRatio", merchantRatio, "platformRatio", platformRatio);
    }

    @Transactional
    public void markExceptionInReview(long exceptionId, long actorUserId, String note) {
        paymentsRepository.updateExceptionStatus(exceptionId, "IN_REVIEW", note, actorUserId);
        auditLogService.logUserEvent(actorUserId, "RECON_EXCEPTION_IN_REVIEW", "reconciliation_exception", String.valueOf(exceptionId), null, Map.of("note", note));
    }

    @Transactional
    public void resolveException(long exceptionId, long actorUserId, String note) {
        paymentsRepository.updateExceptionStatus(exceptionId, "RESOLVED", note, actorUserId);
        auditLogService.logUserEvent(actorUserId, "RECON_EXCEPTION_RESOLVED", "reconciliation_exception", String.valueOf(exceptionId), null, Map.of("note", note));
    }

    @Transactional
    public void reopenException(long exceptionId, long actorUserId, String note) {
        paymentsRepository.updateExceptionStatus(exceptionId, "REOPENED", note, actorUserId);
        auditLogService.logUserEvent(actorUserId, "RECON_EXCEPTION_REOPENED", "reconciliation_exception", String.valueOf(exceptionId), null, Map.of("note", note));
    }

    private int safeCount(Integer value) {
        return value == null ? 0 : value;
    }

    public double paymentFailureRateLastHour() {
        return paymentsRepository.paymentFailureRateLastHour();
    }

    public List<Long> activeLocationIds() {
        return paymentsRepository.activeLocationIds();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to serialize payload", e);
        }
    }

    private String csvCell(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).replace("\"", "\"\"");
        return '"' + text + '"';
    }

    private String normalizeTenderType(String tenderType) {
        String normalized = tenderType == null ? "" : tenderType.trim().toUpperCase();
        if (!ALLOWED_TENDER_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("tenderType must be one of CASH, CHECK, CARD_PRESENT");
        }
        return normalized;
    }
}
