package com.lexibridge.operations.modules.payments.api;

import com.lexibridge.operations.modules.payments.service.PaymentsService;
import com.lexibridge.operations.security.service.AuthorizationScopeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentsApiController {

    private final PaymentsService paymentsService;
    private final AuthorizationScopeService authorizationScopeService;

    public PaymentsApiController(PaymentsService paymentsService,
                                 AuthorizationScopeService authorizationScopeService) {
        this.paymentsService = paymentsService;
        this.authorizationScopeService = authorizationScopeService;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','FRONT_DESK')")
    public Map<String, Object> summary() {
        Long locationId = authorizationScopeService.currentLocationScope().orElse(null);
        return paymentsService.dashboardSummary(locationId);
    }

    @PostMapping("/tenders")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','FRONT_DESK')")
    public Map<String, Object> createTender(@Valid @RequestBody CreateTenderCommand command) {
        authorizationScopeService.assertBookingAccess(command.bookingOrderId);
        authorizationScopeService.assertActorUser(command.createdBy);
        return paymentsService.createTender(
            command.bookingOrderId,
            command.tenderType,
            command.amount,
            command.currency,
            command.terminalId,
            command.terminalTxnId,
            command.createdBy
        );
    }

    @PostMapping("/callbacks")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','FRONT_DESK','DEVICE_SERVICE')")
    public Map<String, Object> callback(@Valid @RequestBody CallbackCommand command) {
        boolean admin = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        Long scopedLocation = authorizationScopeService.currentLocationScope().orElse(null);
        if (!admin && scopedLocation == null) {
            throw new org.springframework.security.access.AccessDeniedException("Location scope not found for callback actor.");
        }
        return paymentsService.processCallback(
            command.terminalId,
            command.terminalTxnId,
            command.payload,
            admin ? null : scopedLocation,
            SecurityContextHolder.getContext().getAuthentication().getName()
        );
    }

    @PostMapping("/refunds")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','FRONT_DESK')")
    public Map<String, Object> requestRefund(@Valid @RequestBody RefundCommand command) {
        authorizationScopeService.assertTenderLocationScope(command.tenderEntryId);
        authorizationScopeService.assertActorUser(command.createdBy);
        return paymentsService.requestRefund(
            command.tenderEntryId,
            command.amount,
            command.currency,
            command.reason,
            command.createdBy
        );
    }

    @PostMapping("/refunds/{refundId}/approve")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public Map<String, String> approveRefund(@PathVariable long refundId,
                                             @Valid @RequestBody ApproveRefundCommand command) {
        authorizationScopeService.assertRefundScope(refundId);
        authorizationScopeService.assertActorUser(command.supervisorUserId);
        paymentsService.approveRefund(refundId, command.supervisorUserId);
        return Map.of("status", "APPROVED");
    }

    @GetMapping("/reconciliation/runs/{runId}/export")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','FRONT_DESK')")
    public ResponseEntity<byte[]> exportRun(@PathVariable long runId,
                                            @RequestParam(defaultValue = "csv") String format) {
        authorizationScopeService.assertReconciliationRunScope(runId);
        byte[] payload = paymentsService.exportReconciliationRun(runId, format);
        String normalized = format == null ? "csv" : format.toLowerCase(Locale.ROOT);
        String extension = "json".equals(normalized) ? "json" : "csv";
        MediaType contentType = "json".equals(extension)
            ? MediaType.APPLICATION_JSON
            : MediaType.parseMediaType("text/csv");
        return ResponseEntity.ok()
            .contentType(contentType)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=reconciliation-run-" + runId + "." + extension)
            .body(payload);
    }

    @PostMapping("/reconciliation")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','FRONT_DESK')")
    public Map<String, Object> reconcile(@Valid @RequestBody ReconcileCommand command) {
        authorizationScopeService.assertLocationAccess(command.locationId);
        authorizationScopeService.assertActorUser(command.actorUserId);
        return paymentsService.runDailyReconciliation(command.locationId, command.businessDate, command.actorUserId);
    }

    @PostMapping("/split-policy")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public Map<String, Object> configureSplitPolicy(@Valid @RequestBody SplitPolicyCommand command) {
        authorizationScopeService.assertLocationAccess(command.locationId);
        authorizationScopeService.assertActorUser(command.actorUserId);
        return paymentsService.configureSplitPolicy(command.locationId, command.merchantRatio, command.platformRatio, command.actorUserId);
    }

    @GetMapping("/reconciliation/exceptions")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','FRONT_DESK')")
    public java.util.List<Map<String, Object>> exceptions(@RequestParam(required = false) String status) {
        return authorizationScopeService.currentLocationScope()
            .map(locationId -> paymentsService.exceptions(locationId, status))
            .orElseGet(() -> paymentsService.exceptions(status));
    }

    @PostMapping("/reconciliation/exceptions/{exceptionId}/in-review")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','FRONT_DESK')")
    public Map<String, String> markInReview(@PathVariable long exceptionId,
                                            @Valid @RequestBody ExceptionActionCommand command) {
        authorizationScopeService.assertReconciliationExceptionScope(exceptionId);
        authorizationScopeService.assertActorUser(command.actorUserId);
        paymentsService.markExceptionInReview(exceptionId, command.actorUserId, command.note);
        return Map.of("status", "IN_REVIEW");
    }

    @PostMapping("/reconciliation/exceptions/{exceptionId}/resolve")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','FRONT_DESK')")
    public Map<String, String> resolve(@PathVariable long exceptionId,
                                       @Valid @RequestBody ExceptionActionCommand command) {
        authorizationScopeService.assertReconciliationExceptionScope(exceptionId);
        authorizationScopeService.assertActorUser(command.actorUserId);
        paymentsService.resolveException(exceptionId, command.actorUserId, command.note);
        return Map.of("status", "RESOLVED");
    }

    @PostMapping("/reconciliation/exceptions/{exceptionId}/reopen")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','FRONT_DESK')")
    public Map<String, String> reopen(@PathVariable long exceptionId,
                                      @Valid @RequestBody ExceptionActionCommand command) {
        authorizationScopeService.assertReconciliationExceptionScope(exceptionId);
        authorizationScopeService.assertActorUser(command.actorUserId);
        paymentsService.reopenException(exceptionId, command.actorUserId, command.note);
        return Map.of("status", "REOPENED");
    }

    public static class CreateTenderCommand {
        @NotNull
        public Long bookingOrderId;
        @NotBlank
        @Pattern(regexp = "(?i)CASH|CHECK|CARD_PRESENT", message = "tenderType must be one of CASH, CHECK, CARD_PRESENT")
        public String tenderType;
        @NotNull
        @DecimalMin("0.01")
        public BigDecimal amount;
        @NotBlank
        public String currency;
        public String terminalId;
        public String terminalTxnId;
        @NotNull
        public Long createdBy;
    }

    public static class CallbackCommand {
        @NotBlank
        public String terminalId;
        @NotBlank
        public String terminalTxnId;
        public Map<String, Object> payload;
    }

    public static class RefundCommand {
        @NotNull
        public Long tenderEntryId;
        @NotNull
        @DecimalMin("0.01")
        public BigDecimal amount;
        @NotBlank
        public String currency;
        @NotBlank
        public String reason;
        @NotNull
        public Long createdBy;
    }

    public static class ApproveRefundCommand {
        @NotNull
        public Long supervisorUserId;
    }

    public static class ReconcileCommand {
        @NotNull
        public Long locationId;
        @NotNull
        public LocalDate businessDate;
        @NotNull
        public Long actorUserId;
    }

    public static class ExceptionActionCommand {
        @NotNull
        public Long actorUserId;
        @NotBlank
        public String note;
    }

    public static class SplitPolicyCommand {
        @NotNull
        public Long locationId;
        @NotNull
        @DecimalMin("0.01")
        public BigDecimal merchantRatio;
        @NotNull
        @DecimalMin("0.01")
        public BigDecimal platformRatio;
        @NotNull
        public Long actorUserId;
    }
}
