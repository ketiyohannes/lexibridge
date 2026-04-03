package com.lexibridge.operations.modules.moderation.service;

import com.lexibridge.operations.modules.moderation.model.ModerationCaseCommand;
import com.lexibridge.operations.modules.moderation.repository.ModerationRepository;
import com.lexibridge.operations.governance.AuditLogService;
import com.lexibridge.operations.modules.content.service.MediaValidationService;
import com.lexibridge.operations.modules.content.model.FileValidationResult;
import com.lexibridge.operations.storage.service.BinaryStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModerationServiceTest {

    @Mock
    private ModerationRepository moderationRepository;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private MediaValidationService mediaValidationService;
    @Mock
    private BinaryStorageService binaryStorageService;

    private ModerationService moderationService;

    @BeforeEach
    void setUp() {
        moderationService = new ModerationService(moderationRepository, auditLogService, mediaValidationService, binaryStorageService);
    }

    @Test
    void createCase_shouldDetectSensitiveTerms() {
        when(moderationRepository.activeSensitiveEntries()).thenReturn(List.of(
            Map.of(
                "normalized_term", "banned",
                "rule_id", 99L,
                "rule_code", "DICT",
                "tag", "ABUSE",
                "severity", "HIGH"
            )
        ));
        when(moderationRepository.locationForTarget("POST", 22L)).thenReturn(1L);
        when(moderationRepository.createCase(anyLong(), anyString(), anyLong(), org.mockito.ArgumentMatchers.anyList())).thenReturn(10L);

        long caseId = moderationService.createCase(new ModerationCaseCommand(1L, "POST", 22L, "This contains banned text"));

        assertEquals(10L, caseId);
        ArgumentCaptor<List<Map<String, Object>>> hitsCaptor = ArgumentCaptor.forClass(List.class);
        verify(moderationRepository).createCase(anyLong(), anyString(), anyLong(), hitsCaptor.capture());
        Map<String, Object> hit = hitsCaptor.getValue().getFirst();
        assertEquals(99L, hit.get("policyRuleId"));
        assertEquals("DICT", hit.get("policyRuleCode"));
        assertEquals("ABUSE", hit.get("tag"));
        assertEquals("HIGH", hit.get("severity"));
        assertTrue(String.valueOf(hit.get("snippet")).contains("banned"));
    }

    @Test
    void resolveCase_shouldAutoSuspendAtThreeViolations() {
        when(moderationRepository.confirmedViolationsInLastDays(55L, 90)).thenReturn(3);
        when(moderationRepository.hasActiveSuspension(55L)).thenReturn(false);

        Map<String, Object> result = moderationService.resolveCase(1L, "REJECTED", 9L, 55L, "bad content", "appeal");

        assertEquals(true, result.get("autoSuspended"));
        verify(moderationRepository).createSuspension(55L, 9L, "Auto suspension after 3 confirmed violations in 90 days", "appeal", 30);
    }

    @Test
    void runSuspensionSweep_shouldSuspendAllEligibleUsers() {
        when(moderationRepository.usersRequiringAutoSuspension()).thenReturn(List.of(5L, 8L));

        int affected = moderationService.runSuspensionSweep();

        assertEquals(2, affected);
        verify(moderationRepository).createSuspension(5L, 1L, "Scheduled suspension sweep after 3 confirmed violations in 90 days", "Auto-generated", 30);
        verify(moderationRepository).createSuspension(8L, 1L, "Scheduled suspension sweep after 3 confirmed violations in 90 days", "Auto-generated", 30);
    }

    @Test
    void addCaseMedia_shouldRejectInvalidAttachment() {
        when(mediaValidationService.validate(org.mockito.ArgumentMatchers.eq("bad.exe"), org.mockito.ArgumentMatchers.any()))
            .thenReturn(new FileValidationResult(false, null, null, "Unsupported media type", "clamav", "blocked"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> moderationService.addCaseMedia(10L, "bad.exe", new byte[]{1}, 3L));

        assertEquals("Attachment rejected: Unsupported media type", ex.getMessage());
    }

    @Test
    void createPostTarget_shouldAutoCreateModerationCaseWhenSensitiveTermFound() {
        when(moderationRepository.createPost(1L, 4L, "title", "contains banned term")).thenReturn(88L);
        when(moderationRepository.activeSensitiveEntries()).thenReturn(List.of(
            Map.of(
                "normalized_term", "banned",
                "rule_id", 77L,
                "rule_code", "DICT",
                "tag", "ABUSE",
                "severity", "HIGH"
            )
        ));
        when(moderationRepository.createCase(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq("POST"), org.mockito.ArgumentMatchers.eq(88L), org.mockito.ArgumentMatchers.anyList()))
            .thenReturn(901L);

        long targetId = moderationService.createPostTarget(1L, 4L, "title", "contains banned term");

        assertEquals(88L, targetId);
        verify(moderationRepository).createCase(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq("POST"), org.mockito.ArgumentMatchers.eq(88L), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void addTargetMedia_shouldPersistCommunityTargetMedia() {
        byte[] bytes = new byte[]{1, 2, 3};
        when(moderationRepository.locationForTarget("POST", 44L)).thenReturn(1L);
        when(mediaValidationService.validate(org.mockito.ArgumentMatchers.eq("post.png"), org.mockito.ArgumentMatchers.same(bytes)))
            .thenReturn(new FileValidationResult(true, "image/png", "abc123", null, "clamav", "clean"));
        when(moderationRepository.insertTargetMedia(
            org.mockito.ArgumentMatchers.eq("POST"),
            org.mockito.ArgumentMatchers.eq(44L),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq("image/png"),
            org.mockito.ArgumentMatchers.eq(3L),
            org.mockito.ArgumentMatchers.eq("abc123"),
            org.mockito.ArgumentMatchers.eq(7L)
        )).thenReturn(9001L);

        Map<String, Object> result = moderationService.addTargetMedia("post", 44L, "post.png", bytes, 7L);

        assertEquals(9001L, result.get("mediaId"));
        assertEquals("POST", result.get("targetType"));
        verify(binaryStorageService).store(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq("abc123"),
            org.mockito.ArgumentMatchers.eq("image/png"),
            org.mockito.ArgumentMatchers.same(bytes)
        );
    }

    @Test
    void assertTargetOwner_shouldRejectNonOwner() {
        when(moderationRepository.authorForTarget("QNA", 55L)).thenReturn(99L);

        assertThrows(
            org.springframework.security.access.AccessDeniedException.class,
            () -> moderationService.assertTargetOwner("qna", 55L, 12L)
        );
    }

    @Test
    void createPostTarget_shouldBlockWhenAuthorHasActiveSuspension() {
        when(moderationRepository.hasActiveSuspension(4L)).thenReturn(true);

        assertThrows(
            org.springframework.security.access.AccessDeniedException.class,
            () -> moderationService.createPostTarget(1L, 4L, "title", "body")
        );

        verify(auditLogService).logUserEvent(
            org.mockito.ArgumentMatchers.eq(4L),
            org.mockito.ArgumentMatchers.eq("SUSPENDED_CONTENT_POST_BLOCKED"),
            org.mockito.ArgumentMatchers.eq("community_target"),
            org.mockito.ArgumentMatchers.eq("NEW"),
            org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.anyMap()
        );
        verify(moderationRepository, never()).createPost(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
