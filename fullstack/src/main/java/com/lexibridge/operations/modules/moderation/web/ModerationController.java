package com.lexibridge.operations.modules.moderation.web;

import com.lexibridge.operations.modules.moderation.model.ModerationCaseCommand;
import com.lexibridge.operations.modules.moderation.service.ModerationService;
import com.lexibridge.operations.security.service.AuthorizationScopeService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;
import java.util.List;

@Controller
public class ModerationController {

    private final ModerationService moderationService;
    private final AuthorizationScopeService authorizationScopeService;

    public ModerationController(ModerationService moderationService,
                                AuthorizationScopeService authorizationScopeService) {
        this.moderationService = moderationService;
        this.authorizationScopeService = authorizationScopeService;
    }

    @GetMapping("/portal/moderation")
    public String moderation(Model model) {
        populateBaseModel(model);
        if (!model.containsAttribute("createCaseForm")) {
            model.addAttribute("createCaseForm", CreateCaseForm.defaults());
        }
        if (!model.containsAttribute("resolveCaseForm")) {
            model.addAttribute("resolveCaseForm", ResolveCaseForm.defaults());
        }
        if (!model.containsAttribute("reportForm")) {
            model.addAttribute("reportForm", ReportForm.defaults());
        }
        return "portal/moderation";
    }

    @GetMapping("/portal/moderation/cases/{caseId}")
    public String moderationCase(@PathVariable long caseId, Model model) {
        authorizationScopeService.assertModerationCaseScope(caseId);
        populateBaseModel(model);
        model.addAttribute("selectedCase", moderationService.caseDetails(caseId));
        model.addAttribute("selectedCaseMedia", moderationService.caseMedia(caseId));
        model.addAttribute("createCaseForm", CreateCaseForm.defaults());
        model.addAttribute("reportForm", ReportForm.defaults());

        ResolveCaseForm resolveCaseForm = ResolveCaseForm.defaults();
        resolveCaseForm.setCaseId(caseId);
        model.addAttribute("resolveCaseForm", resolveCaseForm);
        return "portal/moderation";
    }

    @PostMapping("/portal/moderation/cases")
    public String createCase(@ModelAttribute CreateCaseForm createCaseForm, Model model) {
        populateBaseModel(model);
        model.addAttribute("createCaseForm", createCaseForm);
        model.addAttribute("reportForm", ReportForm.defaults());
        model.addAttribute("resolveCaseForm", ResolveCaseForm.defaults());

        String validationError = validateCreateCaseForm(createCaseForm);
        if (validationError != null) {
            model.addAttribute("moderationError", validationError);
            return "portal/moderation";
        }

        authorizationScopeService.assertLocationAccess(createCaseForm.getLocationId());

        long caseId = moderationService.createCase(new ModerationCaseCommand(
            createCaseForm.getLocationId(),
            createCaseForm.getTargetType(),
            createCaseForm.getTargetId(),
            createCaseForm.getContentText()
        ));
        model.addAttribute("moderationSuccess", "Case #" + caseId + " created and added to queue.");
        model.addAttribute("selectedCase", moderationService.caseDetails(caseId));
        model.addAttribute("selectedCaseMedia", moderationService.caseMedia(caseId));

        ResolveCaseForm resolveCaseForm = ResolveCaseForm.defaults();
        resolveCaseForm.setCaseId(caseId);
        model.addAttribute("resolveCaseForm", resolveCaseForm);
        return "portal/moderation";
    }

    @PostMapping("/portal/moderation/cases/{caseId}/resolve")
    public String resolveCase(@PathVariable long caseId,
                              @ModelAttribute ResolveCaseForm resolveCaseForm,
                              @RequestParam(required = false) String decision,
                              Model model) {
        populateBaseModel(model);
        model.addAttribute("createCaseForm", CreateCaseForm.defaults());
        model.addAttribute("reportForm", ReportForm.defaults());
        resolveCaseForm.setCaseId(caseId);
        model.addAttribute("resolveCaseForm", resolveCaseForm);

        String effectiveDecision = decision != null && !decision.isBlank() ? decision : resolveCaseForm.getDecision();
        String validationError = validateResolveCaseForm(resolveCaseForm, effectiveDecision);
        if (validationError != null) {
            model.addAttribute("selectedCase", moderationService.caseDetails(caseId));
            model.addAttribute("selectedCaseMedia", moderationService.caseMedia(caseId));
            model.addAttribute("moderationError", validationError);
            return "portal/moderation";
        }

        authorizationScopeService.assertModerationCaseScope(caseId);

        Map<String, Object> resolution = moderationService.resolveCase(
            caseId,
            effectiveDecision,
            currentUserId(),
            resolveCaseForm.getOffenderUserId(),
            resolveCaseForm.getReason(),
            resolveCaseForm.getAppealNote()
        );
        model.addAttribute("selectedCase", moderationService.caseDetails(caseId));
        model.addAttribute("selectedCaseMedia", moderationService.caseMedia(caseId));
        model.addAttribute("resolutionResult", resolution);
        model.addAttribute("moderationSuccess", "Case #" + caseId + " resolved.");
        return "portal/moderation";
    }

    @PostMapping("/portal/moderation/reports")
    public String submitReport(@ModelAttribute ReportForm reportForm, Model model) {
        if (canViewModerationQueue()) {
            populateBaseModel(model);
        } else {
            model.addAttribute("summary", Map.of());
            model.addAttribute("cases", List.of());
        }
        model.addAttribute("createCaseForm", CreateCaseForm.defaults());
        model.addAttribute("resolveCaseForm", ResolveCaseForm.defaults());
        model.addAttribute("reportForm", reportForm);

        if (reportForm.getTargetType() == null || reportForm.getTargetType().isBlank() || reportForm.getTargetId() == null || reportForm.getTargetId() <= 0) {
            model.addAttribute("moderationError", "Target type and target ID are required.");
            return "portal/moderation";
        }
        if (reportForm.getReasonText() == null || reportForm.getReasonText().isBlank()) {
            model.addAttribute("moderationError", "Reason is required.");
            return "portal/moderation";
        }

        long reporterUserId = currentUserId();
        authorizationScopeService.assertLocationAccess(reportForm.getLocationId());
        model.addAttribute("reportResult", moderationService.submitUserReport(
            reporterUserId,
            reportForm.getLocationId(),
            reportForm.getTargetType(),
            reportForm.getTargetId(),
            reportForm.getReasonText()
        ));
        model.addAttribute("reportOutcomes", moderationService.reportsByReporter(reporterUserId));
        model.addAttribute("penaltyOutcomes", moderationService.penaltiesForUser(reporterUserId));
        model.addAttribute("moderationSuccess", "User report submitted.");
        return "portal/moderation";
    }

    private String validateCreateCaseForm(CreateCaseForm form) {
        if (form.getLocationId() == null || form.getLocationId() <= 0) {
            return "Location ID must be a positive number.";
        }
        if (form.getTargetType() == null || form.getTargetType().isBlank()) {
            return "Target type is required.";
        }
        if (form.getTargetId() == null || form.getTargetId() <= 0) {
            return "Target ID must be a positive number.";
        }
        if (form.getContentText() == null || form.getContentText().isBlank()) {
            return "Content text is required to run moderation checks.";
        }
        return null;
    }

    private String validateResolveCaseForm(ResolveCaseForm form, String decision) {
        if (form.getOffenderUserId() == null || form.getOffenderUserId() <= 0) {
            return "Offender user ID must be a positive number.";
        }
        if (decision == null || decision.isBlank()) {
            return "Decision is required.";
        }
        if (form.getReason() == null || form.getReason().isBlank()) {
            return "Resolution reason is required.";
        }
        return null;
    }

    private void populateBaseModel(Model model) {
        Long locationId = authorizationScopeService.currentLocationScope().orElse(null);
        model.addAttribute("summary", moderationService.dashboardSummary(locationId));
        model.addAttribute("cases", moderationService.recentCases(locationId, 30));
    }

    private long currentUserId() {
        return authorizationScopeService.requireCurrentUserId();
    }

    private boolean canViewModerationQueue() {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
            .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()) || "ROLE_MODERATOR".equals(authority.getAuthority()));
    }

    public static final class CreateCaseForm {
        private Long locationId;
        private String targetType;
        private Long targetId;
        private String contentText;

        public static CreateCaseForm defaults() {
            CreateCaseForm form = new CreateCaseForm();
            form.setLocationId(1L);
            form.setTargetType("POST");
            return form;
        }

        public Long getLocationId() {
            return locationId;
        }

        public void setLocationId(Long locationId) {
            this.locationId = locationId;
        }

        public String getTargetType() {
            return targetType;
        }

        public void setTargetType(String targetType) {
            this.targetType = targetType;
        }

        public Long getTargetId() {
            return targetId;
        }

        public void setTargetId(Long targetId) {
            this.targetId = targetId;
        }

        public String getContentText() {
            return contentText;
        }

        public void setContentText(String contentText) {
            this.contentText = contentText;
        }
    }

    public static final class ResolveCaseForm {
        private Long caseId;
        private String decision;
        private Long reviewerUserId;
        private Long offenderUserId;
        private String reason;
        private String appealNote;

        public static ResolveCaseForm defaults() {
            ResolveCaseForm form = new ResolveCaseForm();
            form.setDecision("APPROVED");
            form.setReviewerUserId(1L);
            form.setOffenderUserId(1L);
            return form;
        }

        public Long getCaseId() {
            return caseId;
        }

        public void setCaseId(Long caseId) {
            this.caseId = caseId;
        }

        public String getDecision() {
            return decision;
        }

        public void setDecision(String decision) {
            this.decision = decision;
        }

        public Long getReviewerUserId() {
            return reviewerUserId;
        }

        public void setReviewerUserId(Long reviewerUserId) {
            this.reviewerUserId = reviewerUserId;
        }

        public Long getOffenderUserId() {
            return offenderUserId;
        }

        public void setOffenderUserId(Long offenderUserId) {
            this.offenderUserId = offenderUserId;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public String getAppealNote() {
            return appealNote;
        }

        public void setAppealNote(String appealNote) {
            this.appealNote = appealNote;
        }
    }

    public static final class ReportForm {
        private Long locationId;
        private Long reporterUserId;
        private String targetType;
        private Long targetId;
        private String reasonText;

        public static ReportForm defaults() {
            ReportForm form = new ReportForm();
            form.setLocationId(1L);
            form.setReporterUserId(1L);
            form.setTargetType("POST");
            return form;
        }

        public Long getLocationId() { return locationId; }
        public void setLocationId(Long locationId) { this.locationId = locationId; }
        public Long getReporterUserId() { return reporterUserId; }
        public void setReporterUserId(Long reporterUserId) { this.reporterUserId = reporterUserId; }
        public String getTargetType() { return targetType; }
        public void setTargetType(String targetType) { this.targetType = targetType; }
        public Long getTargetId() { return targetId; }
        public void setTargetId(Long targetId) { this.targetId = targetId; }
        public String getReasonText() { return reasonText; }
        public void setReasonText(String reasonText) { this.reasonText = reasonText; }
    }
}
