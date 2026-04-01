package com.lexibridge.operations.modules.leave.web;

import com.lexibridge.operations.modules.leave.model.LeaveRequestCommand;
import com.lexibridge.operations.modules.leave.service.LeaveService;
import com.lexibridge.operations.security.service.AuthorizationScopeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.Map;
import java.io.IOException;

import org.springframework.format.annotation.DateTimeFormat;

@Controller
public class LeaveController {

    private final LeaveService leaveService;
    private final AuthorizationScopeService authorizationScopeService;
    private final ObjectMapper objectMapper;

    public LeaveController(LeaveService leaveService,
                           AuthorizationScopeService authorizationScopeService,
                           ObjectMapper objectMapper) {
        this.leaveService = leaveService;
        this.authorizationScopeService = authorizationScopeService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/portal/leave")
    public String leave(Model model) {
        populateBaseModel(model);
        if (!model.containsAttribute("submitForm")) {
            model.addAttribute("submitForm", SubmitLeaveForm.defaults());
        }
        if (!model.containsAttribute("approvalForm")) {
            model.addAttribute("approvalForm", ApprovalActionForm.defaults());
        }
        if (!model.containsAttribute("requestLookup")) {
            model.addAttribute("requestLookup", RequestLookup.defaults());
        }
        if (!model.containsAttribute("resubmitForm")) {
            model.addAttribute("resubmitForm", CorrectionResubmitForm.defaults());
        }
        if (!model.containsAttribute("formDefinitionCreateForm")) {
            model.addAttribute("formDefinitionCreateForm", FormDefinitionCreateForm.defaults());
        }
        if (!model.containsAttribute("formVersionCreateForm")) {
            model.addAttribute("formVersionCreateForm", FormVersionCreateForm.defaults());
        }
        return "portal/leave";
    }

    @PostMapping("/portal/leave/requests")
    public String submitRequest(@ModelAttribute SubmitLeaveForm submitForm, Model model) {
        populateBaseModel(model);
        model.addAttribute("submitForm", submitForm);
        model.addAttribute("approvalForm", ApprovalActionForm.defaults());
        model.addAttribute("requestLookup", RequestLookup.fromRequester(submitForm.getRequesterUserId()));
        model.addAttribute("resubmitForm", CorrectionResubmitForm.defaults());
        model.addAttribute("formDefinitionCreateForm", FormDefinitionCreateForm.defaults());
        model.addAttribute("formVersionCreateForm", FormVersionCreateForm.defaults());

        String validationError = validateSubmitForm(submitForm);
        if (validationError != null) {
            model.addAttribute("leaveError", validationError);
            return "portal/leave";
        }

        authorizationScopeService.assertLocationAccess(submitForm.getLocationId());
        leaveService.submit(new LeaveRequestCommand(
            submitForm.getLocationId(),
            currentUserId(),
            submitForm.getLeaveType(),
            submitForm.getStartDate(),
            submitForm.getEndDate(),
            submitForm.getDurationMinutes(),
            submitForm.getFormVersionId(),
            Map.of("reason", submitForm.getFormReason() == null ? "" : submitForm.getFormReason())
        ));
        model.addAttribute("leaveSuccess", "Leave request submitted and routed for approval.");
        model.addAttribute("requestLookup", RequestLookup.fromRequester(currentUserId()));
        model.addAttribute("recentRequests", leaveService.recentRequestsByRequester(currentUserId(), 20));
        return "portal/leave";
    }

    @PostMapping("/portal/leave/requests/{requestId}/withdraw")
    public String withdrawRequest(@PathVariable long requestId,
                                  @ModelAttribute RequestLookup requestLookup,
                                  Model model) {
        populateBaseModel(model);
        model.addAttribute("submitForm", SubmitLeaveForm.defaults());
        model.addAttribute("approvalForm", ApprovalActionForm.defaults());
        model.addAttribute("requestLookup", requestLookup);
        model.addAttribute("resubmitForm", CorrectionResubmitForm.defaults());
        model.addAttribute("formDefinitionCreateForm", FormDefinitionCreateForm.defaults());
        model.addAttribute("formVersionCreateForm", FormVersionCreateForm.defaults());

        authorizationScopeService.assertLeaveRequestRequester(requestId);
        boolean withdrawn = leaveService.withdraw(requestId);
        if (!withdrawn) {
            model.addAttribute("leaveError", "Leave request can no longer be withdrawn.");
            if (requestLookup.getRequesterUserId() != null && requestLookup.getRequesterUserId() > 0) {
                model.addAttribute("recentRequests", leaveService.recentRequestsByRequester(requestLookup.getRequesterUserId(), 20));
            }
            return "portal/leave";
        }
        model.addAttribute("leaveSuccess", "Leave request #" + requestId + " withdrawn.");
        if (requestLookup.getRequesterUserId() != null && requestLookup.getRequesterUserId() > 0) {
            model.addAttribute("recentRequests", leaveService.recentRequestsByRequester(requestLookup.getRequesterUserId(), 20));
        }
        return "portal/leave";
    }

    @PostMapping("/portal/leave/approvals/{taskId}/approve")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','HR_APPROVER')")
    public String approveTask(@PathVariable long taskId,
                              @ModelAttribute ApprovalActionForm approvalForm,
                              Model model) {
        populateBaseModel(model);
        model.addAttribute("submitForm", SubmitLeaveForm.defaults());
        model.addAttribute("approvalForm", approvalForm);
        model.addAttribute("requestLookup", RequestLookup.defaults());
        model.addAttribute("resubmitForm", CorrectionResubmitForm.defaults());
        model.addAttribute("formDefinitionCreateForm", FormDefinitionCreateForm.defaults());
        model.addAttribute("formVersionCreateForm", FormVersionCreateForm.defaults());

        String validationError = validateApprovalForm(approvalForm);
        if (validationError != null) {
            model.addAttribute("leaveError", validationError);
            return "portal/leave";
        }

        authorizationScopeService.assertApprovalTaskApprover(taskId);
        leaveService.approveTask(taskId, currentUserId(), approvalForm.getNote());
        model.addAttribute("leaveSuccess", "Approval task #" + taskId + " marked approved.");
        return "portal/leave";
    }

    @PostMapping("/portal/leave/approvals/{taskId}/correction")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','HR_APPROVER')")
    public String requestCorrection(@PathVariable long taskId,
                                    @ModelAttribute ApprovalActionForm approvalForm,
                                    Model model) {
        populateBaseModel(model);
        model.addAttribute("submitForm", SubmitLeaveForm.defaults());
        model.addAttribute("approvalForm", approvalForm);
        model.addAttribute("requestLookup", RequestLookup.defaults());
        model.addAttribute("resubmitForm", CorrectionResubmitForm.defaults());
        model.addAttribute("formDefinitionCreateForm", FormDefinitionCreateForm.defaults());
        model.addAttribute("formVersionCreateForm", FormVersionCreateForm.defaults());

        String validationError = validateApprovalForm(approvalForm);
        if (validationError != null) {
            model.addAttribute("leaveError", validationError);
            return "portal/leave";
        }

        authorizationScopeService.assertApprovalTaskApprover(taskId);
        leaveService.requestCorrection(taskId, currentUserId(), approvalForm.getNote());
        model.addAttribute("leaveSuccess", "Approval task #" + taskId + " returned for correction.");
        return "portal/leave";
    }

    @PostMapping("/portal/leave/requests/lookup")
    public String lookupRequests(@ModelAttribute RequestLookup requestLookup, Model model) {
        populateBaseModel(model);
        model.addAttribute("submitForm", SubmitLeaveForm.defaults());
        model.addAttribute("approvalForm", ApprovalActionForm.defaults());
        model.addAttribute("requestLookup", requestLookup);
        model.addAttribute("resubmitForm", CorrectionResubmitForm.defaults());
        model.addAttribute("formDefinitionCreateForm", FormDefinitionCreateForm.defaults());
        model.addAttribute("formVersionCreateForm", FormVersionCreateForm.defaults());

        long requesterUserId = currentUserId();
        requestLookup.setRequesterUserId(requesterUserId);
        model.addAttribute("recentRequests", leaveService.recentRequestsByRequester(requesterUserId, 20));
        return "portal/leave";
    }

    @PostMapping("/portal/leave/requests/{requestId}/attachments")
    public String uploadAttachment(@PathVariable long requestId,
                                   @RequestParam("file") MultipartFile file,
                                   @ModelAttribute RequestLookup requestLookup,
                                   Model model) throws IOException {
        populateBaseModel(model);
        model.addAttribute("submitForm", SubmitLeaveForm.defaults());
        model.addAttribute("approvalForm", ApprovalActionForm.defaults());
        model.addAttribute("requestLookup", requestLookup);
        model.addAttribute("resubmitForm", CorrectionResubmitForm.defaults());
        model.addAttribute("formDefinitionCreateForm", FormDefinitionCreateForm.defaults());
        model.addAttribute("formVersionCreateForm", FormVersionCreateForm.defaults());

        authorizationScopeService.assertLeaveRequestRequester(requestId);
        leaveService.addAttachment(
            requestId,
            file.getOriginalFilename() == null ? "leave-attachment.bin" : file.getOriginalFilename(),
            file.getBytes(),
            currentUserId()
        );
        model.addAttribute("leaveSuccess", "Attachment uploaded for leave request #" + requestId + ".");
        if (requestLookup.getRequesterUserId() != null && requestLookup.getRequesterUserId() > 0) {
            model.addAttribute("recentRequests", leaveService.recentRequestsByRequester(requestLookup.getRequesterUserId(), 20));
        }
        return "portal/leave";
    }

    @PostMapping("/portal/leave/requests/{requestId}/attachments/lookup")
    public String lookupAttachments(@PathVariable long requestId,
                                    @ModelAttribute RequestLookup requestLookup,
                                    Model model) {
        populateBaseModel(model);
        model.addAttribute("submitForm", SubmitLeaveForm.defaults());
        model.addAttribute("approvalForm", ApprovalActionForm.defaults());
        model.addAttribute("requestLookup", requestLookup);
        model.addAttribute("resubmitForm", CorrectionResubmitForm.defaults());
        model.addAttribute("formDefinitionCreateForm", FormDefinitionCreateForm.defaults());
        model.addAttribute("formVersionCreateForm", FormVersionCreateForm.defaults());

        authorizationScopeService.assertLeaveRequestReadAccess(requestId);
        model.addAttribute("selectedAttachmentRequestId", requestId);
        model.addAttribute("selectedRequestAttachments", leaveService.attachments(requestId));
        if (requestLookup.getRequesterUserId() != null && requestLookup.getRequesterUserId() > 0) {
            model.addAttribute("recentRequests", leaveService.recentRequestsByRequester(requestLookup.getRequesterUserId(), 20));
        }
        return "portal/leave";
    }

    @GetMapping("/portal/leave/requests/{requestId}/attachments/{attachmentId}/download")
    public ResponseEntity<byte[]> downloadAttachment(@PathVariable long requestId,
                                                     @PathVariable long attachmentId) {
        authorizationScopeService.assertLeaveRequestReadAccess(requestId);
        var binary = leaveService.downloadAttachment(requestId, attachmentId);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(binary.mimeType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=leave-attachment-" + attachmentId)
            .body(binary.payload());
    }

    @PostMapping("/portal/leave/requests/{requestId}/resubmit")
    public String resubmitCorrection(@PathVariable long requestId,
                                     @ModelAttribute CorrectionResubmitForm resubmitForm,
                                     @ModelAttribute RequestLookup requestLookup,
                                     Model model) {
        populateBaseModel(model);
        model.addAttribute("submitForm", SubmitLeaveForm.defaults());
        model.addAttribute("approvalForm", ApprovalActionForm.defaults());
        model.addAttribute("requestLookup", requestLookup);
        model.addAttribute("resubmitForm", resubmitForm);
        model.addAttribute("formDefinitionCreateForm", FormDefinitionCreateForm.defaults());
        model.addAttribute("formVersionCreateForm", FormVersionCreateForm.defaults());

        String validationError = validateResubmitForm(resubmitForm);
        if (validationError != null) {
            model.addAttribute("leaveError", validationError);
            if (requestLookup.getRequesterUserId() != null && requestLookup.getRequesterUserId() > 0) {
                model.addAttribute("recentRequests", leaveService.recentRequestsByRequester(requestLookup.getRequesterUserId(), 20));
            }
            return "portal/leave";
        }

        authorizationScopeService.assertLeaveRequestRequester(requestId);
        leaveService.resubmitCorrection(requestId, new LeaveRequestCommand(
            resubmitForm.getLocationId(),
            currentUserId(),
            resubmitForm.getLeaveType(),
            resubmitForm.getStartDate(),
            resubmitForm.getEndDate(),
            resubmitForm.getDurationMinutes(),
            resubmitForm.getFormVersionId(),
            Map.of("reason", resubmitForm.getFormReason() == null ? "" : resubmitForm.getFormReason())
        ));
        model.addAttribute("leaveSuccess", "Leave request #" + requestId + " resubmitted for approval.");
        if (requestLookup.getRequesterUserId() != null && requestLookup.getRequesterUserId() > 0) {
            model.addAttribute("recentRequests", leaveService.recentRequestsByRequester(requestLookup.getRequesterUserId(), 20));
        }
        return "portal/leave";
    }

    @PostMapping("/portal/leave/forms/definitions")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','HR_APPROVER')")
    public String createFormDefinition(@ModelAttribute FormDefinitionCreateForm form,
                                       Model model) {
        populateBaseModel(model);
        model.addAttribute("submitForm", SubmitLeaveForm.defaults());
        model.addAttribute("approvalForm", ApprovalActionForm.defaults());
        model.addAttribute("requestLookup", RequestLookup.defaults());
        model.addAttribute("resubmitForm", CorrectionResubmitForm.defaults());
        model.addAttribute("formDefinitionCreateForm", form);
        model.addAttribute("formVersionCreateForm", FormVersionCreateForm.defaults());

        String validationError = validateFormDefinitionCreateForm(form);
        if (validationError != null) {
            model.addAttribute("leaveError", validationError);
            return "portal/leave";
        }

        authorizationScopeService.assertLocationAccess(form.getLocationId());
        leaveService.createFormDefinition(form.getLocationId(), form.getName(), currentUserId());
        model.addAttribute("leaveSuccess", "Leave form definition created.");
        return "portal/leave";
    }

    @PostMapping("/portal/leave/forms/{definitionId}/versions")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','HR_APPROVER')")
    public String createFormVersion(@PathVariable long definitionId,
                                    @ModelAttribute FormVersionCreateForm form,
                                    Model model) {
        populateBaseModel(model);
        model.addAttribute("submitForm", SubmitLeaveForm.defaults());
        model.addAttribute("approvalForm", ApprovalActionForm.defaults());
        model.addAttribute("requestLookup", RequestLookup.defaults());
        model.addAttribute("resubmitForm", CorrectionResubmitForm.defaults());
        model.addAttribute("formDefinitionCreateForm", FormDefinitionCreateForm.defaults());
        model.addAttribute("formVersionCreateForm", form);

        String validationError = validateFormVersionCreateForm(form);
        if (validationError != null) {
            model.addAttribute("leaveError", validationError);
            return "portal/leave";
        }

        try {
            Map<String, Object> schema = objectMapper.readValue(form.getSchemaJson(), new TypeReference<>() { });
            authorizationScopeService.assertLocationAccess(form.getLocationId());
            leaveService.createFormVersion(definitionId, form.getLocationId(), schema, currentUserId());
            model.addAttribute("leaveSuccess", "Leave form version created for definition #" + definitionId + ".");
        } catch (Exception ex) {
            model.addAttribute("leaveError", "Schema JSON must be a valid JSON object.");
        }
        return "portal/leave";
    }

    private String validateSubmitForm(SubmitLeaveForm form) {
        if (form.getLocationId() == null || form.getLocationId() <= 0) {
            return "Location ID must be a positive number.";
        }
        if (form.getRequesterUserId() == null || form.getRequesterUserId() <= 0) {
            return "Requester user ID must be a positive number.";
        }
        if (form.getLeaveType() == null || form.getLeaveType().isBlank()) {
            return "Leave type is required.";
        }
        if (form.getStartDate() == null || form.getEndDate() == null) {
            return "Start date and end date are required.";
        }
        if (form.getEndDate().isBefore(form.getStartDate())) {
            return "End date cannot be before start date.";
        }
        if (form.getDurationMinutes() == null || form.getDurationMinutes() <= 0) {
            return "Duration minutes must be positive.";
        }
        return null;
    }

    private String validateApprovalForm(ApprovalActionForm form) {
        return null;
    }

    private String validateResubmitForm(CorrectionResubmitForm form) {
        if (form.getLocationId() == null || form.getLocationId() <= 0) {
            return "Location ID must be a positive number for resubmission.";
        }
        if (form.getLeaveType() == null || form.getLeaveType().isBlank()) {
            return "Leave type is required for resubmission.";
        }
        if (form.getStartDate() == null || form.getEndDate() == null) {
            return "Start date and end date are required for resubmission.";
        }
        if (form.getEndDate().isBefore(form.getStartDate())) {
            return "End date cannot be before start date.";
        }
        if (form.getDurationMinutes() == null || form.getDurationMinutes() <= 0) {
            return "Duration minutes must be positive.";
        }
        return null;
    }

    private String validateFormDefinitionCreateForm(FormDefinitionCreateForm form) {
        if (form.getLocationId() == null || form.getLocationId() <= 0) {
            return "Location ID is required for form definition.";
        }
        if (form.getName() == null || form.getName().isBlank()) {
            return "Form name is required.";
        }
        return null;
    }

    private String validateFormVersionCreateForm(FormVersionCreateForm form) {
        if (form.getLocationId() == null || form.getLocationId() <= 0) {
            return "Location ID is required for form version.";
        }
        if (form.getSchemaJson() == null || form.getSchemaJson().isBlank()) {
            return "Schema JSON is required.";
        }
        return null;
    }

    private long currentUserId() {
        return authorizationScopeService.requireCurrentUserId();
    }

    private void populateBaseModel(Model model) {
        Long locationId = authorizationScopeService.currentLocationScope().orElse(null);
        long formLocationId = locationId == null ? 1L : locationId;
        model.addAttribute("summary", leaveService.dashboardSummary(locationId));
        model.addAttribute("approvalQueue", leaveService.recentApprovalQueue(locationId, 25));
        model.addAttribute("leaveFormDefinitions", leaveService.formDefinitions(formLocationId));
        model.addAttribute("leaveFormVersions", leaveService.activeFormVersions(formLocationId));
        model.addAttribute("formManagementLocationId", formLocationId);
    }

    public static final class SubmitLeaveForm {
        private Long locationId;
        private Long requesterUserId;
        private String leaveType;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate startDate;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate endDate;
        private Integer durationMinutes;
        private Long formVersionId;
        private String formReason;

        public static SubmitLeaveForm defaults() {
            SubmitLeaveForm form = new SubmitLeaveForm();
            form.setLocationId(1L);
            form.setRequesterUserId(1L);
            form.setLeaveType("ANNUAL_LEAVE");
            form.setStartDate(LocalDate.now().plusDays(1));
            form.setEndDate(LocalDate.now().plusDays(1));
            form.setDurationMinutes(480);
            form.setFormVersionId(1L);
            return form;
        }

        public Long getLocationId() {
            return locationId;
        }

        public void setLocationId(Long locationId) {
            this.locationId = locationId;
        }

        public Long getRequesterUserId() {
            return requesterUserId;
        }

        public void setRequesterUserId(Long requesterUserId) {
            this.requesterUserId = requesterUserId;
        }

        public String getLeaveType() {
            return leaveType;
        }

        public void setLeaveType(String leaveType) {
            this.leaveType = leaveType;
        }

        public LocalDate getStartDate() {
            return startDate;
        }

        public void setStartDate(LocalDate startDate) {
            this.startDate = startDate;
        }

        public LocalDate getEndDate() {
            return endDate;
        }

        public void setEndDate(LocalDate endDate) {
            this.endDate = endDate;
        }

        public Integer getDurationMinutes() {
            return durationMinutes;
        }

        public void setDurationMinutes(Integer durationMinutes) {
            this.durationMinutes = durationMinutes;
        }

        public Long getFormVersionId() {
            return formVersionId;
        }

        public void setFormVersionId(Long formVersionId) {
            this.formVersionId = formVersionId;
        }

        public String getFormReason() {
            return formReason;
        }

        public void setFormReason(String formReason) {
            this.formReason = formReason;
        }
    }

    public static final class ApprovalActionForm {
        private String note;

        public static ApprovalActionForm defaults() {
            return new ApprovalActionForm();
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }

    public static final class RequestLookup {
        private Long requesterUserId;

        public static RequestLookup defaults() {
            return new RequestLookup();
        }

        public static RequestLookup fromRequester(Long requesterUserId) {
            RequestLookup lookup = new RequestLookup();
            lookup.setRequesterUserId(requesterUserId);
            return lookup;
        }

        public Long getRequesterUserId() {
            return requesterUserId;
        }

        public void setRequesterUserId(Long requesterUserId) {
            this.requesterUserId = requesterUserId;
        }
    }

    public static final class CorrectionResubmitForm {
        private Long locationId;
        private String leaveType;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate startDate;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate endDate;
        private Integer durationMinutes;
        private Long formVersionId;
        private String formReason;

        public static CorrectionResubmitForm defaults() {
            CorrectionResubmitForm form = new CorrectionResubmitForm();
            form.setLocationId(1L);
            form.setLeaveType("ANNUAL_LEAVE");
            form.setStartDate(LocalDate.now().plusDays(1));
            form.setEndDate(LocalDate.now().plusDays(1));
            form.setDurationMinutes(480);
            form.setFormVersionId(1L);
            return form;
        }

        public Long getLocationId() {
            return locationId;
        }

        public void setLocationId(Long locationId) {
            this.locationId = locationId;
        }

        public String getLeaveType() {
            return leaveType;
        }

        public void setLeaveType(String leaveType) {
            this.leaveType = leaveType;
        }

        public LocalDate getStartDate() {
            return startDate;
        }

        public void setStartDate(LocalDate startDate) {
            this.startDate = startDate;
        }

        public LocalDate getEndDate() {
            return endDate;
        }

        public void setEndDate(LocalDate endDate) {
            this.endDate = endDate;
        }

        public Integer getDurationMinutes() {
            return durationMinutes;
        }

        public void setDurationMinutes(Integer durationMinutes) {
            this.durationMinutes = durationMinutes;
        }

        public Long getFormVersionId() {
            return formVersionId;
        }

        public void setFormVersionId(Long formVersionId) {
            this.formVersionId = formVersionId;
        }

        public String getFormReason() {
            return formReason;
        }

        public void setFormReason(String formReason) {
            this.formReason = formReason;
        }
    }

    public static final class FormDefinitionCreateForm {
        private Long locationId;
        private String name;

        public static FormDefinitionCreateForm defaults() {
            FormDefinitionCreateForm form = new FormDefinitionCreateForm();
            form.setLocationId(1L);
            return form;
        }

        public Long getLocationId() {
            return locationId;
        }

        public void setLocationId(Long locationId) {
            this.locationId = locationId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public static final class FormVersionCreateForm {
        private Long locationId;
        private String schemaJson;

        public static FormVersionCreateForm defaults() {
            FormVersionCreateForm form = new FormVersionCreateForm();
            form.setLocationId(1L);
            form.setSchemaJson("{\"fields\":[{\"id\":\"reason\",\"type\":\"text\",\"required\":true}]}");
            return form;
        }

        public Long getLocationId() {
            return locationId;
        }

        public void setLocationId(Long locationId) {
            this.locationId = locationId;
        }

        public String getSchemaJson() {
            return schemaJson;
        }

        public void setSchemaJson(String schemaJson) {
            this.schemaJson = schemaJson;
        }
    }
}
