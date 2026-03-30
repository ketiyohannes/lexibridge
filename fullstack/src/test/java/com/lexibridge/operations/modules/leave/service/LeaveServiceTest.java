package com.lexibridge.operations.modules.leave.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexibridge.operations.modules.content.service.MediaValidationService;
import com.lexibridge.operations.modules.leave.model.LeaveRequestCommand;
import com.lexibridge.operations.modules.leave.repository.LeaveRepository;
import com.lexibridge.operations.governance.AuditLogService;
import com.lexibridge.operations.storage.service.BinaryStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeaveServiceTest {

    @Mock
    private LeaveRepository leaveRepository;
    @Mock
    private ApprovalRoutingService approvalRoutingService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private MediaValidationService mediaValidationService;
    @Mock
    private BinaryStorageService binaryStorageService;

    private LeaveService leaveService;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);
        leaveService = new LeaveService(
            leaveRepository,
            approvalRoutingService,
            auditLogService,
            new ObjectMapper(),
            mediaValidationService,
            binaryStorageService,
            fixedClock
        );
    }

    @Test
    void submit_shouldCreateTaskForApproverUserRule() {
        LeaveRequestCommand command = new LeaveRequestCommand(
            1L,
            11L,
            "ANNUAL",
            LocalDate.of(2026, 5, 1),
            LocalDate.of(2026, 5, 2),
            480,
            null,
            Map.of()
        );
        when(leaveRepository.createRequest(anyLong(), anyLong(), anyString(), any(), any(), anyInt(), any(), any(), any())).thenReturn(99L);
        when(leaveRepository.requesterOrgUnit(11L)).thenReturn(Optional.of(3L));
        when(leaveRepository.matchingRules(anyLong(), any(), anyString(), anyInt())).thenReturn(List.of(Map.of("priority", 1, "approver_user_id", 44L, "approver_role_code", "MANAGER")));
        when(approvalRoutingService.pickBestRule(org.mockito.ArgumentMatchers.anyList())).thenReturn(Map.of("priority", 1, "approver_user_id", 44L, "approver_role_code", "MANAGER"));

        Map<String, Object> result = leaveService.submit(command);

        assertEquals(99L, result.get("leaveRequestId"));
        assertEquals(44L, result.get("approverUserId"));
        verify(leaveRepository).createApprovalTask(anyLong(), anyLong(), any());
    }

    @Test
    void markOverdueApprovals_shouldUpdateAllTasks() {
        when(leaveRepository.overdueApprovals()).thenReturn(List.of(1L, 2L, 3L));

        int count = leaveService.markOverdueApprovals();

        assertEquals(3, count);
        verify(leaveRepository).markTaskOverdue(1L);
        verify(leaveRepository).markTaskOverdue(2L);
        verify(leaveRepository).markTaskOverdue(3L);
    }

    @Test
    void withdraw_shouldReturnFalseWhenRequestCannotTransition() {
        when(leaveRepository.withdraw(90L)).thenReturn(0);

        boolean result = leaveService.withdraw(90L);

        assertFalse(result);
    }

    @Test
    void withdraw_shouldReturnTrueWhenRequestIsWithdrawn() {
        when(leaveRepository.withdraw(91L)).thenReturn(1);

        boolean result = leaveService.withdraw(91L);

        assertTrue(result);
    }

    @Test
    void resubmitCorrection_shouldRouteBackToApprover() {
        LeaveRequestCommand command = new LeaveRequestCommand(
            1L,
            11L,
            "ANNUAL",
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 2),
            480,
            null,
            Map.of("reason", "fixed")
        );
        when(leaveRepository.requestById(99L)).thenReturn(Map.of(
            "id", 99L,
            "location_id", 1L,
            "requester_user_id", 11L,
            "status", "NEEDS_CORRECTION"
        ));
        when(leaveRepository.resubmitCorrection(anyLong(), anyString(), any(), any(), anyInt(), any(), any(), any())).thenReturn(1);
        when(leaveRepository.requesterOrgUnit(11L)).thenReturn(Optional.of(3L));
        when(leaveRepository.matchingRules(anyLong(), any(), anyString(), anyInt())).thenReturn(List.of(Map.of("priority", 1, "approver_user_id", 44L, "approver_role_code", "MANAGER")));
        when(approvalRoutingService.pickBestRule(org.mockito.ArgumentMatchers.anyList())).thenReturn(Map.of("priority", 1, "approver_user_id", 44L, "approver_role_code", "MANAGER"));

        Map<String, Object> result = leaveService.resubmitCorrection(99L, command);

        assertEquals(99L, result.get("leaveRequestId"));
        assertEquals(44L, result.get("approverUserId"));
        verify(leaveRepository).createApprovalTask(anyLong(), anyLong(), any());
    }

    @Test
    void resubmitCorrection_shouldRejectWhenRequestNotInCorrectionState() {
        LeaveRequestCommand command = new LeaveRequestCommand(1L, 11L, "ANNUAL", LocalDate.now(), LocalDate.now(), 60, null, Map.of());
        when(leaveRepository.requestById(100L)).thenReturn(Map.of(
            "id", 100L,
            "location_id", 1L,
            "requester_user_id", 11L,
            "status", "APPROVED"
        ));

        assertThrows(IllegalStateException.class, () -> leaveService.resubmitCorrection(100L, command));
    }

    @Test
    void withSlaState_shouldMarkActiveOverdueAndPaused() {
        List<Map<String, Object>> rows = List.of(
            Map.of("id", 1L, "sla_deadline_at", LocalDate.of(2026, 3, 1).atTime(12, 0), "status", "PENDING_APPROVAL"),
            Map.of("id", 2L, "sla_deadline_at", LocalDate.of(2026, 3, 1).atTime(9, 0), "status", "PENDING_APPROVAL"),
            Map.of("id", 3L, "sla_deadline_at", LocalDate.of(2026, 3, 1).atTime(11, 0), "status", "NEEDS_CORRECTION", "sla_paused", true)
        );

        List<Map<String, Object>> mapped = leaveService.withSlaState(rows);

        assertEquals("ACTIVE", mapped.get(0).get("sla_state"));
        assertEquals("OVERDUE", mapped.get(1).get("sla_state"));
        assertEquals("PAUSED", mapped.get(2).get("sla_state"));
    }

    @Test
    void approveTask_shouldAuditUsingProvidedActorId() {
        when(leaveRepository.approveTask(12L, "ok")).thenReturn(1);

        leaveService.approveTask(12L, 44L, "ok");

        verify(auditLogService).logUserEvent(eq(44L), eq("LEAVE_APPROVED"), eq("approval_task"), eq("12"), eq(null), any());
    }

    @Test
    void createFormDefinition_shouldPersistDefinition() {
        when(leaveRepository.createFormDefinition(1L, "Default Form", 44L)).thenReturn(15L);

        Map<String, Object> result = leaveService.createFormDefinition(1L, "Default Form", 44L);

        assertEquals(15L, result.get("formDefinitionId"));
        verify(leaveRepository).createFormDefinition(1L, "Default Form", 44L);
    }

    @Test
    void createFormVersion_shouldPersistNextVersion() {
        when(leaveRepository.formDefinitionLocation(8L)).thenReturn(1L);
        when(leaveRepository.nextFormVersionNo(8L)).thenReturn(3);
        when(leaveRepository.createFormVersion(eq(8L), eq(3), anyString(), eq(44L))).thenReturn(21L);

        Map<String, Object> result = leaveService.createFormVersion(8L, 1L, Map.of("fields", List.of(Map.of("id", "reason"))), 44L);

        assertEquals(21L, result.get("formVersionId"));
        assertEquals(3, result.get("versionNo"));
        verify(leaveRepository).createFormVersion(eq(8L), eq(3), anyString(), eq(44L));
    }
}
