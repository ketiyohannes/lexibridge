package com.lexibridge.operations.modules.payments.web;

import com.lexibridge.operations.modules.payments.service.PaymentsService;
import com.lexibridge.operations.security.service.AuthorizationScopeService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

@Controller
public class PaymentsController {

    private final PaymentsService paymentsService;
    private final AuthorizationScopeService authorizationScopeService;

    public PaymentsController(PaymentsService paymentsService,
                              AuthorizationScopeService authorizationScopeService) {
        this.paymentsService = paymentsService;
        this.authorizationScopeService = authorizationScopeService;
    }

    @GetMapping("/portal/payments")
    public String payments(Model model) {
        populateBaseModel(model);
        if (!model.containsAttribute("tenderForm")) {
            model.addAttribute("tenderForm", TenderForm.defaults());
        }
        if (!model.containsAttribute("refundForm")) {
            model.addAttribute("refundForm", RefundForm.defaults());
        }
        if (!model.containsAttribute("reconForm")) {
            model.addAttribute("reconForm", ReconciliationForm.defaults());
        }
        if (!model.containsAttribute("exceptionActionForm")) {
            model.addAttribute("exceptionActionForm", ExceptionActionForm.defaults());
        }
        if (!model.containsAttribute("splitPolicyForm")) {
            model.addAttribute("splitPolicyForm", SplitPolicyForm.defaults());
        }
        return "portal/payments";
    }

    @PostMapping("/portal/payments/tenders")
    public String createTender(@ModelAttribute TenderForm tenderForm, Model model) {
        populateBaseModel(model);
        model.addAttribute("tenderForm", tenderForm);
        model.addAttribute("refundForm", RefundForm.defaults());
        model.addAttribute("reconForm", ReconciliationForm.defaults());
        model.addAttribute("exceptionActionForm", ExceptionActionForm.defaults());
        model.addAttribute("splitPolicyForm", SplitPolicyForm.defaults());

        String validationError = validateTenderForm(tenderForm);
        if (validationError != null) {
            model.addAttribute("paymentsError", validationError);
            return "portal/payments";
        }
        try {
            authorizationScopeService.assertBookingAccess(tenderForm.getBookingOrderId());
            Map<String, Object> result = paymentsService.createTender(
                tenderForm.getBookingOrderId(),
                tenderForm.getTenderType(),
                tenderForm.getAmount(),
                tenderForm.getCurrency(),
                tenderForm.getTerminalId(),
                tenderForm.getTerminalTxnId(),
                currentUserId()
            );
            model.addAttribute("tenderResult", result);
            model.addAttribute("paymentsSuccess", "Tender created successfully.");
        } catch (RuntimeException ex) {
            model.addAttribute("paymentsError", ex.getMessage());
        }
        return "portal/payments";
    }

    @PostMapping("/portal/payments/refunds")
    public String requestRefund(@ModelAttribute RefundForm refundForm, Model model) {
        populateBaseModel(model);
        model.addAttribute("tenderForm", TenderForm.defaults());
        model.addAttribute("refundForm", refundForm);
        model.addAttribute("reconForm", ReconciliationForm.defaults());
        model.addAttribute("exceptionActionForm", ExceptionActionForm.defaults());
        model.addAttribute("splitPolicyForm", SplitPolicyForm.defaults());

        String validationError = validateRefundForm(refundForm);
        if (validationError != null) {
            model.addAttribute("paymentsError", validationError);
            return "portal/payments";
        }

        try {
            authorizationScopeService.assertTenderLocationScope(refundForm.getTenderEntryId());
            Map<String, Object> result = paymentsService.requestRefund(
                refundForm.getTenderEntryId(),
                refundForm.getAmount(),
                refundForm.getCurrency(),
                refundForm.getReason(),
                currentUserId()
            );
            model.addAttribute("refundResult", result);
            model.addAttribute("paymentsSuccess", "Refund request submitted.");
        } catch (RuntimeException ex) {
            model.addAttribute("paymentsError", ex.getMessage());
        }
        return "portal/payments";
    }

    @PostMapping("/portal/payments/refunds/{refundId}/approve")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public String approveRefund(@PathVariable long refundId,
                                @ModelAttribute ExceptionActionForm exceptionActionForm,
                                Model model) {
        populateBaseModel(model);
        model.addAttribute("tenderForm", TenderForm.defaults());
        model.addAttribute("refundForm", RefundForm.defaults());
        model.addAttribute("reconForm", ReconciliationForm.defaults());
        model.addAttribute("exceptionActionForm", exceptionActionForm);
        model.addAttribute("splitPolicyForm", SplitPolicyForm.defaults());

        authorizationScopeService.assertRefundScope(refundId);
        paymentsService.approveRefund(refundId, currentUserId());
        model.addAttribute("paymentsSuccess", "Refund #" + refundId + " approved.");
        return "portal/payments";
    }

    @PostMapping("/portal/payments/reconciliation")
    public String runReconciliation(@ModelAttribute ReconciliationForm reconForm, Model model) {
        populateBaseModel(model);
        model.addAttribute("tenderForm", TenderForm.defaults());
        model.addAttribute("refundForm", RefundForm.defaults());
        model.addAttribute("reconForm", reconForm);
        model.addAttribute("exceptionActionForm", ExceptionActionForm.defaults());
        model.addAttribute("splitPolicyForm", SplitPolicyForm.defaults());

        if (reconForm.getLocationId() == null || reconForm.getLocationId() <= 0 || reconForm.getBusinessDate() == null) {
            model.addAttribute("paymentsError", "Location and business date are required.");
            return "portal/payments";
        }

        authorizationScopeService.assertLocationAccess(reconForm.getLocationId());

        Map<String, Object> result = paymentsService.runDailyReconciliation(
            reconForm.getLocationId(),
            reconForm.getBusinessDate(),
            currentUserId()
        );
        model.addAttribute("reconResult", result);
        model.addAttribute("paymentsSuccess", "Reconciliation run completed.");
        return "portal/payments";
    }

    @PostMapping("/portal/payments/exceptions/{exceptionId}/in-review")
    public String markInReview(@PathVariable long exceptionId,
                               @ModelAttribute ExceptionActionForm exceptionActionForm,
                               Model model) {
        return updateException(exceptionId, "IN_REVIEW", exceptionActionForm, model);
    }

    @PostMapping("/portal/payments/exceptions/{exceptionId}/resolve")
    public String resolveException(@PathVariable long exceptionId,
                                   @ModelAttribute ExceptionActionForm exceptionActionForm,
                                   Model model) {
        return updateException(exceptionId, "RESOLVED", exceptionActionForm, model);
    }

    @PostMapping("/portal/payments/exceptions/{exceptionId}/reopen")
    public String reopenException(@PathVariable long exceptionId,
                                  @ModelAttribute ExceptionActionForm exceptionActionForm,
                                  Model model) {
        return updateException(exceptionId, "REOPENED", exceptionActionForm, model);
    }

    private String updateException(long exceptionId,
                                   String action,
                                   ExceptionActionForm exceptionActionForm,
                                   Model model) {
        populateBaseModel(model);
        model.addAttribute("tenderForm", TenderForm.defaults());
        model.addAttribute("refundForm", RefundForm.defaults());
        model.addAttribute("reconForm", ReconciliationForm.defaults());
        model.addAttribute("exceptionActionForm", exceptionActionForm);
        model.addAttribute("splitPolicyForm", SplitPolicyForm.defaults());

        if (exceptionActionForm.getNote() == null || exceptionActionForm.getNote().isBlank()) {
            model.addAttribute("paymentsError", "A workflow note is required for exception updates.");
            return "portal/payments";
        }

        authorizationScopeService.assertReconciliationExceptionScope(exceptionId);

        switch (action) {
            case "IN_REVIEW" -> paymentsService.markExceptionInReview(exceptionId, currentUserId(), exceptionActionForm.getNote());
            case "RESOLVED" -> paymentsService.resolveException(exceptionId, currentUserId(), exceptionActionForm.getNote());
            case "REOPENED" -> paymentsService.reopenException(exceptionId, currentUserId(), exceptionActionForm.getNote());
            default -> throw new IllegalArgumentException("Unsupported exception action: " + action);
        }
        model.addAttribute("paymentsSuccess", "Exception #" + exceptionId + " updated to " + action + ".");
        return "portal/payments";
    }

    @PostMapping("/portal/payments/split-policy")
    public String updateSplitPolicy(@ModelAttribute SplitPolicyForm splitPolicyForm, Model model) {
        populateBaseModel(model);
        model.addAttribute("tenderForm", TenderForm.defaults());
        model.addAttribute("refundForm", RefundForm.defaults());
        model.addAttribute("reconForm", ReconciliationForm.defaults());
        model.addAttribute("exceptionActionForm", ExceptionActionForm.defaults());
        model.addAttribute("splitPolicyForm", splitPolicyForm);

        authorizationScopeService.assertLocationAccess(splitPolicyForm.getLocationId());

        paymentsService.configureSplitPolicy(
            splitPolicyForm.getLocationId(),
            splitPolicyForm.getMerchantRatio(),
            splitPolicyForm.getPlatformRatio(),
            currentUserId()
        );
        model.addAttribute("paymentsSuccess", "Split policy updated.");
        return "portal/payments";
    }

    @GetMapping("/portal/payments/reconciliation/runs/{runId}/export")
    public ResponseEntity<byte[]> exportReconciliationRun(@PathVariable long runId,
                                                          @org.springframework.web.bind.annotation.RequestParam(defaultValue = "csv") String format) {
        authorizationScopeService.assertReconciliationRunScope(runId);
        byte[] payload = paymentsService.exportReconciliationRun(runId, format);
        String normalized = format == null ? "csv" : format.toLowerCase();
        String extension = "json".equals(normalized) ? "json" : "csv";
        MediaType mediaType = "json".equals(extension)
            ? MediaType.APPLICATION_JSON
            : MediaType.parseMediaType("text/csv");
        return ResponseEntity.ok()
            .contentType(mediaType)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=reconciliation-run-" + runId + "." + extension)
            .body(payload);
    }

    private String validateTenderForm(TenderForm form) {
        if (form.getBookingOrderId() == null || form.getBookingOrderId() <= 0) {
            return "Booking order ID must be a positive number.";
        }
        if (form.getAmount() == null || form.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return "Tender amount must be greater than zero.";
        }
        if (form.getCurrency() == null || form.getCurrency().isBlank()) {
            return "Currency is required.";
        }
        return null;
    }

    private String validateRefundForm(RefundForm form) {
        if (form.getTenderEntryId() == null || form.getTenderEntryId() <= 0) {
            return "Tender entry ID must be a positive number.";
        }
        if (form.getAmount() == null || form.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return "Refund amount must be greater than zero.";
        }
        if (form.getReason() == null || form.getReason().isBlank()) {
            return "Refund reason is required.";
        }
        return null;
    }

    private void populateBaseModel(Model model) {
        java.util.Optional<Long> scopedLocation = authorizationScopeService.currentLocationScope();
        if (scopedLocation.isPresent()) {
            long locationId = scopedLocation.get();
            model.addAttribute("summary", paymentsService.dashboardSummary(locationId));
            model.addAttribute("exceptions", paymentsService.exceptions(locationId, null));
            model.addAttribute("recentTenders", paymentsService.recentTenders(locationId, 20));
            model.addAttribute("recentRefunds", paymentsService.recentRefunds(locationId, 20));
            model.addAttribute("recentRuns", paymentsService.recentReconciliationRuns(locationId, 20));
            return;
        }
        model.addAttribute("summary", paymentsService.dashboardSummary());
        model.addAttribute("exceptions", paymentsService.exceptions(null));
        model.addAttribute("recentTenders", paymentsService.recentTenders(20));
        model.addAttribute("recentRefunds", paymentsService.recentRefunds(20));
        model.addAttribute("recentRuns", paymentsService.recentReconciliationRuns(20));
    }

    private long currentUserId() {
        return authorizationScopeService.requireCurrentUserId();
    }

    public static final class TenderForm {
        private Long bookingOrderId;
        private String tenderType;
        private BigDecimal amount;
        private String currency;
        private String terminalId;
        private String terminalTxnId;
        private Long createdBy;

        public static TenderForm defaults() {
            TenderForm form = new TenderForm();
            form.setTenderType("CARD_PRESENT");
            form.setAmount(BigDecimal.valueOf(25.00));
            form.setCurrency("USD");
            form.setCreatedBy(1L);
            return form;
        }

        public Long getBookingOrderId() { return bookingOrderId; }
        public void setBookingOrderId(Long bookingOrderId) { this.bookingOrderId = bookingOrderId; }
        public String getTenderType() { return tenderType; }
        public void setTenderType(String tenderType) { this.tenderType = tenderType; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
        public String getCurrency() { return currency; }
        public void setCurrency(String currency) { this.currency = currency; }
        public String getTerminalId() { return terminalId; }
        public void setTerminalId(String terminalId) { this.terminalId = terminalId; }
        public String getTerminalTxnId() { return terminalTxnId; }
        public void setTerminalTxnId(String terminalTxnId) { this.terminalTxnId = terminalTxnId; }
        public Long getCreatedBy() { return createdBy; }
        public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    }

    public static final class RefundForm {
        private Long tenderEntryId;
        private BigDecimal amount;
        private String currency;
        private String reason;
        private Long createdBy;

        public static RefundForm defaults() {
            RefundForm form = new RefundForm();
            form.setAmount(BigDecimal.valueOf(10.00));
            form.setCurrency("USD");
            form.setCreatedBy(1L);
            return form;
        }

        public Long getTenderEntryId() { return tenderEntryId; }
        public void setTenderEntryId(Long tenderEntryId) { this.tenderEntryId = tenderEntryId; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
        public String getCurrency() { return currency; }
        public void setCurrency(String currency) { this.currency = currency; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        public Long getCreatedBy() { return createdBy; }
        public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    }

    public static final class ReconciliationForm {
        private Long locationId;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate businessDate;
        private Long actorUserId;

        public static ReconciliationForm defaults() {
            ReconciliationForm form = new ReconciliationForm();
            form.setLocationId(1L);
            form.setBusinessDate(LocalDate.now());
            form.setActorUserId(1L);
            return form;
        }

        public Long getLocationId() { return locationId; }
        public void setLocationId(Long locationId) { this.locationId = locationId; }
        public LocalDate getBusinessDate() { return businessDate; }
        public void setBusinessDate(LocalDate businessDate) { this.businessDate = businessDate; }
        public Long getActorUserId() { return actorUserId; }
        public void setActorUserId(Long actorUserId) { this.actorUserId = actorUserId; }
    }

    public static final class ExceptionActionForm {
        private Long actorUserId;
        private String note;

        public static ExceptionActionForm defaults() {
            ExceptionActionForm form = new ExceptionActionForm();
            form.setActorUserId(1L);
            form.setNote("Reviewed by operator");
            return form;
        }

        public Long getActorUserId() { return actorUserId; }
        public void setActorUserId(Long actorUserId) { this.actorUserId = actorUserId; }
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
    }

    public static final class SplitPolicyForm {
        private Long locationId;
        private BigDecimal merchantRatio;
        private BigDecimal platformRatio;
        private Long actorUserId;

        public static SplitPolicyForm defaults() {
            SplitPolicyForm form = new SplitPolicyForm();
            form.setLocationId(1L);
            form.setMerchantRatio(BigDecimal.valueOf(80));
            form.setPlatformRatio(BigDecimal.valueOf(20));
            form.setActorUserId(1L);
            return form;
        }

        public Long getLocationId() { return locationId; }
        public void setLocationId(Long locationId) { this.locationId = locationId; }
        public BigDecimal getMerchantRatio() { return merchantRatio; }
        public void setMerchantRatio(BigDecimal merchantRatio) { this.merchantRatio = merchantRatio; }
        public BigDecimal getPlatformRatio() { return platformRatio; }
        public void setPlatformRatio(BigDecimal platformRatio) { this.platformRatio = platformRatio; }
        public Long getActorUserId() { return actorUserId; }
        public void setActorUserId(Long actorUserId) { this.actorUserId = actorUserId; }
    }
}
