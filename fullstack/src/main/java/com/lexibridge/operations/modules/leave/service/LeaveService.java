package com.lexibridge.operations.modules.leave.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexibridge.operations.governance.AuditLogService;
import com.lexibridge.operations.modules.content.model.FileValidationResult;
import com.lexibridge.operations.modules.content.service.MediaValidationService;
import com.lexibridge.operations.modules.leave.model.LeaveRequestCommand;
import com.lexibridge.operations.modules.leave.repository.LeaveRepository;
import com.lexibridge.operations.storage.service.BinaryStorageService;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class LeaveService {

    private final LeaveRepository leaveRepository;
    private final ApprovalRoutingService approvalRoutingService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final MediaValidationService mediaValidationService;
    private final BinaryStorageService binaryStorageService;
    private final Clock clock;

    public LeaveService(LeaveRepository leaveRepository,
                        ApprovalRoutingService approvalRoutingService,
                         AuditLogService auditLogService,
                         ObjectMapper objectMapper,
                         MediaValidationService mediaValidationService,
                         BinaryStorageService binaryStorageService,
                         Clock clock) {
        this.leaveRepository = leaveRepository;
        this.approvalRoutingService = approvalRoutingService;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.mediaValidationService = mediaValidationService;
        this.binaryStorageService = binaryStorageService;
        this.clock = clock;
    }

    public Map<String, Object> dashboardSummary() {
        return leaveRepository.summary(null);
    }

    public Map<String, Object> dashboardSummary(Long locationId) {
        return leaveRepository.summary(locationId);
    }

    public List<Map<String, Object>> recentApprovalQueue(int limit) {
        return withSlaState(leaveRepository.recentApprovalQueue(null, limit));
    }

    public List<Map<String, Object>> recentApprovalQueue(Long locationId, int limit) {
        return withSlaState(leaveRepository.recentApprovalQueue(locationId, limit));
    }

    public List<Map<String, Object>> recentRequestsByRequester(long requesterUserId, int limit) {
        return withSlaState(leaveRepository.recentLeaveRequestsByRequester(requesterUserId, limit));
    }

    public List<Map<String, Object>> activeFormVersions(long locationId) {
        return leaveRepository.activeFormVersions(locationId);
    }

    public List<Map<String, Object>> formDefinitions(long locationId) {
        return leaveRepository.formDefinitions(locationId);
    }

    @Transactional
    public Map<String, Object> createFormDefinition(long locationId, String name, long actorUserId) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Form name is required.");
        }
        long definitionId = leaveRepository.createFormDefinition(locationId, normalized, actorUserId);
        auditLogService.logUserEvent(actorUserId, "LEAVE_FORM_DEFINITION_CREATED", "leave_form_definition", String.valueOf(definitionId), locationId, Map.of("name", normalized));
        return Map.of("formDefinitionId", definitionId, "locationId", locationId, "name", normalized, "status", "CREATED");
    }

    @Transactional
    public Map<String, Object> createFormVersion(long formDefinitionId,
                                                 long expectedLocationId,
                                                 Map<String, Object> schema,
                                                 long actorUserId) {
        if (schema == null || schema.isEmpty()) {
            throw new IllegalArgumentException("Schema payload is required.");
        }
        Long locationId = leaveRepository.formDefinitionLocation(formDefinitionId);
        if (locationId == null) {
            throw new IllegalArgumentException("Active form definition not found.");
        }
        if (!locationId.equals(expectedLocationId)) {
            throw new IllegalArgumentException("Form definition does not belong to the requested location.");
        }
        int versionNo = leaveRepository.nextFormVersionNo(formDefinitionId);
        long versionId = leaveRepository.createFormVersion(formDefinitionId, versionNo, toJson(schema), actorUserId);
        auditLogService.logUserEvent(actorUserId, "LEAVE_FORM_VERSION_CREATED", "leave_form_version", String.valueOf(versionId), locationId, Map.of("formDefinitionId", formDefinitionId, "versionNo", versionNo));
        return Map.of(
            "formVersionId", versionId,
            "formDefinitionId", formDefinitionId,
            "versionNo", versionNo,
            "locationId", locationId,
            "status", "CREATED"
        );
    }

    @Transactional
    public Map<String, Object> submit(LeaveRequestCommand command) {
        LocalDateTime slaDeadline = plusBusinessHours(LocalDateTime.now(clock), 8);
        if (command.formVersionId() != null && !leaveRepository.formVersionBelongsToLocation(command.locationId(), command.formVersionId())) {
            throw new IllegalArgumentException("Form version does not belong to the request location.");
        }
        long leaveRequestId = leaveRepository.createRequest(
            command.locationId(),
            command.requesterUserId(),
            command.leaveType(),
            command.startDate(),
            command.endDate(),
            command.durationMinutes(),
            command.formVersionId(),
            toJson(command.formPayload()),
            slaDeadline
        );

        long approverUserId = resolveApproverUserId(
            command.locationId(),
            command.requesterUserId(),
            command.leaveType(),
            command.durationMinutes()
        );
        leaveRepository.createApprovalTask(leaveRequestId, approverUserId, slaDeadline);
        leaveRepository.updateRequestStep(leaveRequestId, "PENDING_MANAGER_OR_HR_APPROVAL");
        auditLogService.logUserEvent(command.requesterUserId(), "LEAVE_SUBMITTED", "leave_request", String.valueOf(leaveRequestId), command.locationId(), Map.of("approverUserId", approverUserId));
        return Map.of("leaveRequestId", leaveRequestId, "approverUserId", approverUserId, "slaDeadline", slaDeadline.toString());
    }

    @Transactional
    public Map<String, Object> resubmitCorrection(long leaveRequestId,
                                                  LeaveRequestCommand command) {
        Map<String, Object> existing = leaveRepository.requestById(leaveRequestId);
        if (existing == null) {
            throw new IllegalArgumentException("Leave request not found.");
        }
        if (!"NEEDS_CORRECTION".equals(String.valueOf(existing.get("status")))) {
            throw new IllegalStateException("Leave request is not in correction state.");
        }
        long locationId = ((Number) existing.get("location_id")).longValue();
        long requesterUserId = ((Number) existing.get("requester_user_id")).longValue();
        if (command.locationId() != locationId || command.requesterUserId() != requesterUserId) {
            throw new IllegalArgumentException("Correction resubmission must match original requester and location.");
        }
        if (command.formVersionId() != null && !leaveRepository.formVersionBelongsToLocation(command.locationId(), command.formVersionId())) {
            throw new IllegalArgumentException("Form version does not belong to the request location.");
        }

        LocalDateTime slaDeadline = plusBusinessHours(LocalDateTime.now(clock), 8);
        int updated = leaveRepository.resubmitCorrection(
            leaveRequestId,
            command.leaveType(),
            command.startDate(),
            command.endDate(),
            command.durationMinutes(),
            command.formVersionId(),
            toJson(command.formPayload()),
            slaDeadline
        );
        if (updated == 0) {
            throw new IllegalStateException("Leave request is not in correction state.");
        }

        long approverUserId = resolveApproverUserId(
            command.locationId(),
            command.requesterUserId(),
            command.leaveType(),
            command.durationMinutes()
        );
        leaveRepository.createApprovalTask(leaveRequestId, approverUserId, slaDeadline);
        leaveRepository.updateRequestStep(leaveRequestId, "PENDING_MANAGER_OR_HR_APPROVAL");
        auditLogService.logUserEvent(
            command.requesterUserId(),
            "LEAVE_RESUBMITTED",
            "leave_request",
            String.valueOf(leaveRequestId),
            command.locationId(),
            Map.of("approverUserId", approverUserId)
        );
        return Map.of("leaveRequestId", leaveRequestId, "approverUserId", approverUserId, "slaDeadline", slaDeadline.toString());
    }

    @Transactional
    public boolean withdraw(long leaveRequestId) {
        int updated = leaveRepository.withdraw(leaveRequestId);
        if (updated == 0) {
            return false;
        }
        auditLogService.logSystemEvent("LEAVE_WITHDRAWN", "leave_request", String.valueOf(leaveRequestId), null, Map.of());
        return true;
    }

    @Transactional
    public int markOverdueApprovals() {
        List<Long> overdue = leaveRepository.overdueApprovals();
        for (Long taskId : overdue) {
            leaveRepository.markTaskOverdue(taskId);
        }
        return overdue.size();
    }

    @Transactional
    public Map<String, Object> approveTask(long taskId, long approverUserId, String note) {
        int updated = leaveRepository.approveTask(taskId, note);
        if (updated == 0) {
            throw new IllegalArgumentException("Approval task not found or already decided: " + taskId);
        }
        auditLogService.logUserEvent(approverUserId, "LEAVE_APPROVED", "approval_task", String.valueOf(taskId), null, Map.of("note", note == null ? "" : note));
        return Map.of("taskId", taskId, "status", "APPROVED");
    }

    @Transactional
    public Map<String, Object> requestCorrection(long taskId, long approverUserId, String note) {
        int updated = leaveRepository.returnTaskForCorrection(taskId, note);
        if (updated == 0) {
            throw new IllegalArgumentException("Approval task not found or already decided: " + taskId);
        }
        auditLogService.logUserEvent(approverUserId, "LEAVE_NEEDS_CORRECTION", "approval_task", String.valueOf(taskId), null, Map.of("note", note == null ? "" : note));
        return Map.of("taskId", taskId, "status", "NEEDS_CORRECTION");
    }

    @Transactional
    public Map<String, Object> addAttachment(long leaveRequestId,
                                             String filename,
                                             byte[] bytes,
                                             long actorUserId) {
        FileValidationResult validation = mediaValidationService.validate(filename, bytes);
        if (!validation.valid()) {
            throw new IllegalArgumentException("Attachment rejected: " + validation.reason());
        }
        if (leaveRepository.leaveRequestLocation(leaveRequestId) == null) {
            throw new IllegalArgumentException("Leave request not found.");
        }
        String storagePath = "leave-attachments/" + leaveRequestId + "/" + validation.checksumSha256();
        binaryStorageService.store(storagePath, validation.checksumSha256(), validation.detectedMime(), bytes);
        long attachmentId = leaveRepository.addAttachment(
            leaveRequestId,
            storagePath,
            validation.detectedMime(),
            bytes.length,
            validation.checksumSha256(),
            actorUserId
        );
        auditLogService.logUserEvent(actorUserId, "LEAVE_ATTACHMENT_ADDED", "leave_request_attachment", String.valueOf(attachmentId), null, Map.of("leaveRequestId", leaveRequestId));
        return Map.of("attachmentId", attachmentId, "leaveRequestId", leaveRequestId, "status", "UPLOADED");
    }

    public List<Map<String, Object>> attachments(long leaveRequestId) {
        return leaveRepository.attachments(leaveRequestId);
    }

    public BinaryStorageService.DownloadedBinary downloadAttachment(long leaveRequestId, long attachmentId) {
        Map<String, Object> attachment = leaveRepository.attachmentById(attachmentId);
        if (attachment == null) {
            throw new IllegalArgumentException("Leave attachment not found.");
        }
        long actualRequestId = ((Number) attachment.get("leave_request_id")).longValue();
        if (actualRequestId != leaveRequestId) {
            throw new IllegalArgumentException("Leave attachment does not belong to requested leave request.");
        }
        return binaryStorageService.read(String.valueOf(attachment.get("storage_path")));
    }

    private LocalDateTime plusBusinessHours(LocalDateTime start, int businessHours) {
        LocalDateTime cursor = start;
        int remaining = businessHours;
        while (remaining > 0) {
            cursor = cursor.plusHours(1);
            if (cursor.getDayOfWeek() != DayOfWeek.SATURDAY && cursor.getDayOfWeek() != DayOfWeek.SUNDAY) {
                remaining--;
            }
        }
        return cursor;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to serialize leave form payload.", ex);
        }
    }

    List<Map<String, Object>> withSlaState(List<Map<String, Object>> rows) {
        LocalDateTime nowUtc = LocalDateTime.now(clock);
        return rows.stream()
            .map(row -> {
                Map<String, Object> mutable = new java.util.LinkedHashMap<>(row);
                boolean paused = Boolean.TRUE.equals(row.get("sla_paused"))
                    || "NEEDS_CORRECTION".equals(String.valueOf(row.get("request_status")))
                    || "NEEDS_CORRECTION".equals(String.valueOf(row.get("status")))
                    || "REQUESTER_CORRECTION".equals(String.valueOf(row.get("current_step")));
                LocalDateTime deadline = asLocalDateTime(row.get("sla_deadline_at"));
                if (deadline == null) {
                    deadline = asLocalDateTime(row.get("due_at"));
                }

                if (paused) {
                    mutable.put("sla_state", "PAUSED");
                    mutable.put("sla_countdown_seconds", 0L);
                    mutable.put("sla_countdown_label", "PAUSED");
                    return Map.copyOf(mutable);
                }
                if (deadline == null) {
                    mutable.put("sla_state", "UNKNOWN");
                    mutable.put("sla_countdown_seconds", 0L);
                    mutable.put("sla_countdown_label", "UNKNOWN");
                    return Map.copyOf(mutable);
                }

                long seconds = java.time.Duration.between(nowUtc, deadline).getSeconds();
                mutable.put("sla_countdown_seconds", seconds);
                mutable.put("sla_state", seconds < 0 ? "OVERDUE" : "ACTIVE");
                mutable.put("sla_countdown_label", formatCountdown(seconds));
                return Map.copyOf(mutable);
            })
            .toList();
    }

    private LocalDateTime asLocalDateTime(Object value) {
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        return null;
    }

    private String formatCountdown(long seconds) {
        long absolute = Math.abs(seconds);
        long hours = absolute / 3600;
        long minutes = (absolute % 3600) / 60;
        String suffix = String.format("%02dh %02dm", hours, minutes);
        return seconds < 0 ? "-" + suffix : suffix;
    }

    private long resolveApproverUserId(long locationId,
                                       long requesterUserId,
                                       String leaveType,
                                       int durationMinutes) {
        Long orgUnitId = leaveRepository.requesterOrgUnit(requesterUserId).orElse(null);
        List<Map<String, Object>> rules = leaveRepository.matchingRules(locationId, orgUnitId, leaveType, durationMinutes);
        Map<String, Object> selectedRule = approvalRoutingService.pickBestRule(rules);
        Long approverUserId = (Long) selectedRule.get("approver_user_id");
        if (approverUserId != null) {
            return approverUserId;
        }
        String roleCode = (String) selectedRule.get("approver_role_code");
        return leaveRepository.findApproverByRole(locationId, roleCode)
            .orElseThrow(() -> new IllegalStateException("No approver available for role " + roleCode));
    }
}
