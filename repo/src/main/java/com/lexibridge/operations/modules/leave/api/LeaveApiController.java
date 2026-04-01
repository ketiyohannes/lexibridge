package com.lexibridge.operations.modules.leave.api;

import com.lexibridge.operations.modules.leave.model.LeaveRequestCommand;
import com.lexibridge.operations.modules.leave.service.LeaveService;
import com.lexibridge.operations.security.service.AuthorizationScopeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/leave")
@PreAuthorize("hasAnyRole('ADMIN','EMPLOYEE','MANAGER','HR_APPROVER')")
public class LeaveApiController {

    private final LeaveService leaveService;
    private final AuthorizationScopeService authorizationScopeService;

    public LeaveApiController(LeaveService leaveService,
                              AuthorizationScopeService authorizationScopeService) {
        this.leaveService = leaveService;
        this.authorizationScopeService = authorizationScopeService;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Long locationId = authorizationScopeService.currentLocationScope().orElse(null);
        return leaveService.dashboardSummary(locationId);
    }

    @GetMapping("/forms")
    public List<Map<String, Object>> forms(@RequestParam long locationId) {
        authorizationScopeService.assertLocationAccess(locationId);
        return leaveService.activeFormVersions(locationId);
    }

    @GetMapping("/forms/definitions")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','HR_APPROVER')")
    public List<Map<String, Object>> formDefinitions(@RequestParam long locationId) {
        authorizationScopeService.assertLocationAccess(locationId);
        return leaveService.formDefinitions(locationId);
    }

    @PostMapping("/forms/definitions")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','HR_APPROVER')")
    public Map<String, Object> createFormDefinition(@Valid @RequestBody CreateFormDefinitionCommand command) {
        authorizationScopeService.assertLocationAccess(command.locationId);
        return leaveService.createFormDefinition(command.locationId, command.name, authorizationScopeService.requireCurrentUserId());
    }

    @PostMapping("/forms/definitions/{definitionId}/versions")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','HR_APPROVER')")
    public Map<String, Object> createFormVersion(@PathVariable long definitionId,
                                                  @Valid @RequestBody CreateFormVersionCommand command) {
        authorizationScopeService.assertLocationAccess(command.locationId);
        return leaveService.createFormVersion(definitionId, command.locationId, command.schema, authorizationScopeService.requireCurrentUserId());
    }

    @PostMapping("/requests")
    public Map<String, Object> submit(@Valid @RequestBody SubmitLeaveCommand command) {
        authorizationScopeService.assertLocationAccess(command.locationId);
        authorizationScopeService.assertActorUser(command.requesterUserId);
        return leaveService.submit(new LeaveRequestCommand(
            command.locationId,
            command.requesterUserId,
            command.leaveType,
            command.startDate,
            command.endDate,
            command.durationMinutes,
            command.formVersionId,
            command.formPayload
        ));
    }

    @PostMapping("/requests/{requestId}/withdraw")
    public Map<String, String> withdraw(@PathVariable long requestId) {
        authorizationScopeService.assertLeaveRequestRequester(requestId);
        if (!leaveService.withdraw(requestId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Leave request can no longer be withdrawn.");
        }
        return Map.of("status", "WITHDRAWN");
    }

    @PostMapping("/requests/{requestId}/resubmit")
    public Map<String, Object> resubmitCorrection(@PathVariable long requestId,
                                                  @Valid @RequestBody SubmitLeaveCommand command) {
        authorizationScopeService.assertLeaveRequestRequester(requestId);
        authorizationScopeService.assertLocationAccess(command.locationId);
        authorizationScopeService.assertActorUser(command.requesterUserId);
        return leaveService.resubmitCorrection(requestId, new LeaveRequestCommand(
            command.locationId,
            command.requesterUserId,
            command.leaveType,
            command.startDate,
            command.endDate,
            command.durationMinutes,
            command.formVersionId,
            command.formPayload
        ));
    }

    @PostMapping("/approvals/{taskId}/approve")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','HR_APPROVER')")
    public Map<String, Object> approveTask(@PathVariable long taskId,
                                           @RequestBody Map<String, Object> payload) {
        authorizationScopeService.assertApprovalTaskApprover(taskId);
        return leaveService.approveTask(taskId, authorizationScopeService.requireCurrentUserId(), parseApprovalNote(payload));
    }

    @PostMapping("/approvals/{taskId}/correction")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','HR_APPROVER')")
    public Map<String, Object> requestCorrection(@PathVariable long taskId,
                                                    @RequestBody Map<String, Object> payload) {
        authorizationScopeService.assertApprovalTaskApprover(taskId);
        return leaveService.requestCorrection(taskId, authorizationScopeService.requireCurrentUserId(), parseApprovalNote(payload));
    }

    @PostMapping("/requests/{requestId}/attachments")
    public Map<String, Object> addAttachment(@PathVariable long requestId,
                                             @RequestParam long actorUserId,
                                             @RequestParam("file") MultipartFile file) throws IOException {
        authorizationScopeService.assertActorUser(actorUserId);
        authorizationScopeService.assertLeaveRequestRequester(requestId);
        return leaveService.addAttachment(
            requestId,
            file.getOriginalFilename() == null ? "leave-attachment.bin" : file.getOriginalFilename(),
            file.getBytes(),
            actorUserId
        );
    }

    @GetMapping("/requests/{requestId}/attachments")
    public List<Map<String, Object>> attachments(@PathVariable long requestId) {
        authorizationScopeService.assertLeaveRequestReadAccess(requestId);
        return leaveService.attachments(requestId);
    }

    @GetMapping("/requests/{requestId}/attachments/{attachmentId}/download")
    public ResponseEntity<byte[]> downloadAttachment(@PathVariable long requestId,
                                                     @PathVariable long attachmentId) {
        authorizationScopeService.assertLeaveRequestReadAccess(requestId);
        var binary = leaveService.downloadAttachment(requestId, attachmentId);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(binary.mimeType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=leave-attachment-" + attachmentId)
            .body(binary.payload());
    }

    public static class SubmitLeaveCommand {
        @NotNull
        public Long locationId;
        @NotNull
        public Long requesterUserId;
        @NotBlank
        public String leaveType;
        @NotNull
        public LocalDate startDate;
        @NotNull
        public LocalDate endDate;
        @Min(1)
        public Integer durationMinutes;
        public Long formVersionId;
        public Map<String, Object> formPayload;
    }

    public static class CreateFormDefinitionCommand {
        @NotNull
        public Long locationId;
        @NotBlank
        public String name;
    }

    public static class CreateFormVersionCommand {
        @NotNull
        public Long locationId;
        @NotNull
        public Map<String, Object> schema;
    }

    private String parseApprovalNote(Map<String, Object> payload) {
        if (payload == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Approval payload is required.");
        }
        if (payload.keySet().stream().anyMatch(key -> !"note".equals(key))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unexpected approval payload field.");
        }
        Object note = payload.get("note");
        if (note == null) {
            return null;
        }
        if (note instanceof String noteText) {
            return noteText;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Field 'note' must be a string.");
    }
}
