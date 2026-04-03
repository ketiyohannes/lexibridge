package com.lexibridge.operations.modules.moderation.service;

import com.lexibridge.operations.governance.AuditLogService;
import com.lexibridge.operations.modules.content.model.FileValidationResult;
import com.lexibridge.operations.modules.content.service.MediaValidationService;
import com.lexibridge.operations.modules.moderation.model.ModerationCaseCommand;
import com.lexibridge.operations.modules.moderation.repository.ModerationRepository;
import com.lexibridge.operations.storage.service.BinaryStorageService;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ModerationService {

    private final ModerationRepository moderationRepository;
    private final AuditLogService auditLogService;
    private final MediaValidationService mediaValidationService;
    private final BinaryStorageService binaryStorageService;

    public ModerationService(ModerationRepository moderationRepository,
                             AuditLogService auditLogService,
                             MediaValidationService mediaValidationService,
                             BinaryStorageService binaryStorageService) {
        this.moderationRepository = moderationRepository;
        this.auditLogService = auditLogService;
        this.mediaValidationService = mediaValidationService;
        this.binaryStorageService = binaryStorageService;
    }

    public Map<String, Object> dashboardSummary() {
        return moderationRepository.summary(null);
    }

    public Map<String, Object> dashboardSummary(Long locationId) {
        return moderationRepository.summary(locationId);
    }

    public List<Map<String, Object>> recentCases(int limit) {
        return moderationRepository.recentCases(null, limit);
    }

    public List<Map<String, Object>> recentCases(Long locationId, int limit) {
        return moderationRepository.recentCases(locationId, limit);
    }

    public Map<String, Object> caseDetails(long caseId) {
        return moderationRepository.caseById(caseId)
            .orElseThrow(() -> new IllegalArgumentException("Moderation case not found: " + caseId));
    }

    @Transactional
    public Map<String, Object> submitUserReport(long reporterUserId,
                                                long locationId,
                                                String targetType,
                                                long targetId,
                                                String reasonText) {
        assertTargetLocation(targetType, targetId, locationId);
        long reportId = moderationRepository.createUserReport(locationId, reporterUserId, targetType, targetId, reasonText);
        auditLogService.logUserEvent(reporterUserId, "USER_REPORT_SUBMITTED", "user_report", String.valueOf(reportId), locationId, Map.of("targetType", targetType, "targetId", targetId));
        return Map.of("reportId", reportId, "disposition", "OPEN");
    }

    @Transactional
    public Map<String, Object> resolveUserReport(long reportId,
                                                 String disposition,
                                                 long moderatorUserId,
                                                 String resolutionNote,
                                                 String penaltySummary) {
        String normalized = disposition == null ? "" : disposition.trim().toUpperCase();
        if (!List.of("DISMISSED", "ACTION_TAKEN", "ESCALATED").contains(normalized)) {
            throw new IllegalArgumentException("Disposition must be DISMISSED, ACTION_TAKEN, or ESCALATED.");
        }
        int updated = moderationRepository.resolveUserReport(reportId, normalized, moderatorUserId, resolutionNote, penaltySummary);
        if (updated == 0) {
            throw new IllegalArgumentException("Report not found: " + reportId);
        }
        auditLogService.logUserEvent(moderatorUserId, "USER_REPORT_RESOLVED", "user_report", String.valueOf(reportId), null, Map.of("disposition", normalized));
        return Map.of("reportId", reportId, "disposition", normalized);
    }

    public List<Map<String, Object>> reportsByReporter(long reporterUserId) {
        return moderationRepository.reportsByReporter(reporterUserId);
    }

    public List<Map<String, Object>> penaltiesForUser(long userId) {
        return moderationRepository.penaltiesForUser(userId);
    }

    @Transactional
    public long createCase(ModerationCaseCommand command) {
        assertTargetLocation(command.targetType(), command.targetId(), command.locationId());
        List<Map<String, Object>> hits = detectSensitiveHits(command.contentText());
        long caseId = moderationRepository.createCase(command.locationId(), command.targetType(), command.targetId(), hits);
        auditLogService.logSystemEvent("MODERATION_CASE_CREATED", "moderation_case", String.valueOf(caseId), command.locationId(), Map.of("targetType", command.targetType(), "targetId", command.targetId()));
        return caseId;
    }

    @Transactional
    public Map<String, Object> resolveCase(long caseId,
                                           String decision,
                                           long reviewerUserId,
                                           long offenderUserId,
                                           String reason,
                                           String appealNote) {
        String normalizedDecision = decision == null ? "" : decision.trim().toUpperCase();
        if (!List.of("APPROVED", "REJECTED").contains(normalizedDecision)) {
            throw new IllegalArgumentException("Decision must be APPROVED or REJECTED.");
        }

        moderationRepository.resolveCase(caseId, normalizedDecision, reviewerUserId, reason);
        auditLogService.logUserEvent(reviewerUserId, "MODERATION_CASE_RESOLVED", "moderation_case", String.valueOf(caseId), null, Map.of("decision", normalizedDecision));

        boolean suspended = false;
        if ("REJECTED".equals(normalizedDecision)) {
            moderationRepository.recordConfirmedViolation(offenderUserId, reviewerUserId, reason, appealNote);
            int violations = moderationRepository.confirmedViolationsInLastDays(offenderUserId, 90);
            if (violations >= 3 && !moderationRepository.hasActiveSuspension(offenderUserId)) {
                moderationRepository.createSuspension(
                    offenderUserId,
                    reviewerUserId,
                    "Auto suspension after 3 confirmed violations in 90 days",
                    appealNote,
                    30
                );
                suspended = true;
            }
            return Map.of("decision", normalizedDecision, "confirmedViolationsIn90Days", violations, "autoSuspended", suspended);
        }

        return Map.of("decision", normalizedDecision, "autoSuspended", false);
    }

    @Transactional
    public int runSuspensionSweep() {
        List<Long> users = moderationRepository.usersRequiringAutoSuspension();
        for (Long userId : users) {
            moderationRepository.createSuspension(
                userId,
                1L,
                "Scheduled suspension sweep after 3 confirmed violations in 90 days",
                "Auto-generated",
                30
            );
        }
        return users.size();
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase().replaceAll("\\p{Punct}", " ").replaceAll("\\s+", " ").trim();
    }

    private String snippet(String original, String matchedTerm) {
        if (original == null || original.isBlank()) {
            return "";
        }
        String lower = original.toLowerCase();
        int idx = lower.indexOf(matchedTerm.toLowerCase());
        if (idx < 0) {
            return original.length() <= 120 ? original : original.substring(0, 120);
        }
        int start = Math.max(0, idx - 20);
        int end = Math.min(original.length(), idx + matchedTerm.length() + 20);
        return original.substring(start, end);
    }

    @Transactional(noRollbackFor = AccessDeniedException.class)
    public long createPostTarget(long locationId, long authorUserId, String title, String bodyHtml) {
        enforcePostingAllowed(authorUserId, locationId, "POST", null);
        long postId = moderationRepository.createPost(locationId, authorUserId, title, bodyHtml);
        autoPreScreen(locationId, "POST", postId, String.valueOf(title) + " " + String.valueOf(bodyHtml));
        return postId;
    }

    @Transactional(noRollbackFor = AccessDeniedException.class)
    public long createCommentTarget(long locationId, long postId, long authorUserId, String bodyText) {
        enforcePostingAllowed(authorUserId, locationId, "COMMENT", postId);
        Long postLocation = moderationRepository.locationForTarget("POST", postId);
        if (postLocation == null || !postLocation.equals(locationId)) {
            throw new IllegalArgumentException("Parent post not found in the requested location.");
        }
        long commentId = moderationRepository.createComment(locationId, postId, authorUserId, bodyText);
        autoPreScreen(locationId, "COMMENT", commentId, bodyText);
        return commentId;
    }

    @Transactional(noRollbackFor = AccessDeniedException.class)
    public long createQnaTarget(long locationId, long authorUserId, String questionText, String answerText) {
        enforcePostingAllowed(authorUserId, locationId, "QNA", null);
        long qnaId = moderationRepository.createQna(locationId, authorUserId, questionText, answerText);
        autoPreScreen(locationId, "QNA", qnaId, String.valueOf(questionText) + " " + String.valueOf(answerText));
        return qnaId;
    }

    @Transactional
    public Map<String, Object> addCaseMedia(long caseId,
                                            String filename,
                                            byte[] bytes,
                                            long actorUserId) {
        FileValidationResult validation = mediaValidationService.validate(filename, bytes);
        if (!validation.valid()) {
            throw new IllegalArgumentException("Attachment rejected: " + validation.reason());
        }
        String storagePath = "moderation-media/" + caseId + "/" + validation.checksumSha256();
        binaryStorageService.store(storagePath, validation.checksumSha256(), validation.detectedMime(), bytes);
        long mediaId = moderationRepository.insertCaseMedia(
            caseId,
            storagePath,
            validation.detectedMime(),
            bytes.length,
            validation.checksumSha256(),
            actorUserId
        );
        auditLogService.logUserEvent(actorUserId, "MODERATION_MEDIA_UPLOADED", "moderation_case_media", String.valueOf(mediaId), null, Map.of("caseId", caseId));
        return Map.of("mediaId", mediaId, "caseId", caseId, "status", "UPLOADED");
    }

    public List<Map<String, Object>> caseMedia(long caseId) {
        return moderationRepository.caseMedia(caseId);
    }

    public BinaryStorageService.DownloadedBinary downloadCaseMedia(long caseId, long mediaId) {
        Map<String, Object> media = moderationRepository.caseMediaById(mediaId);
        if (media == null) {
            throw new IllegalArgumentException("Moderation case media not found.");
        }
        long actualCaseId = ((Number) media.get("case_id")).longValue();
        if (actualCaseId != caseId) {
            throw new IllegalArgumentException("Moderation case media does not belong to requested case.");
        }
        return binaryStorageService.read(String.valueOf(media.get("storage_path")));
    }

    @Transactional
    public Map<String, Object> addTargetMedia(String targetType,
                                              long targetId,
                                              String filename,
                                              byte[] bytes,
                                              long actorUserId) {
        String normalizedTargetType = normalizeTargetType(targetType);
        Long locationId = moderationRepository.locationForTarget(normalizedTargetType, targetId);
        if (locationId == null) {
            throw new IllegalArgumentException("Target content not found for supported target type.");
        }

        FileValidationResult validation = mediaValidationService.validate(filename, bytes);
        if (!validation.valid()) {
            throw new IllegalArgumentException("Attachment rejected: " + validation.reason());
        }

        String storagePath = "moderation-target-media/"
            + normalizedTargetType.toLowerCase(Locale.ROOT)
            + "/"
            + targetId
            + "/"
            + validation.checksumSha256()
            + "."
            + extension(filename);
        binaryStorageService.store(storagePath, validation.checksumSha256(), validation.detectedMime(), bytes);
        long mediaId = moderationRepository.insertTargetMedia(
            normalizedTargetType,
            targetId,
            storagePath,
            validation.detectedMime(),
            bytes.length,
            validation.checksumSha256(),
            actorUserId
        );

        auditLogService.logUserEvent(
            actorUserId,
            "MODERATION_TARGET_MEDIA_UPLOADED",
            "community_target_media",
            String.valueOf(mediaId),
            locationId,
            Map.of("targetType", normalizedTargetType, "targetId", targetId)
        );

        return Map.of(
            "mediaId", mediaId,
            "targetType", normalizedTargetType,
            "targetId", targetId,
            "status", "UPLOADED"
        );
    }

    public List<Map<String, Object>> targetMedia(String targetType, long targetId) {
        String normalizedTargetType = normalizeTargetType(targetType);
        if (moderationRepository.locationForTarget(normalizedTargetType, targetId) == null) {
            throw new IllegalArgumentException("Target content not found for supported target type.");
        }
        return moderationRepository.targetMedia(normalizedTargetType, targetId);
    }

    public List<Map<String, Object>> caseTargetMedia(long caseId) {
        Map<String, Object> details = caseDetails(caseId);
        String targetType = String.valueOf(details.get("target_type"));
        long targetId = ((Number) details.get("target_id")).longValue();
        return targetMedia(targetType, targetId);
    }

    public BinaryStorageService.DownloadedBinary downloadTargetMedia(String targetType, long targetId, long mediaId) {
        String normalizedTargetType = normalizeTargetType(targetType);
        Map<String, Object> media = moderationRepository.targetMediaById(mediaId);
        if (media == null) {
            throw new IllegalArgumentException("Target media not found.");
        }

        String actualTargetType = String.valueOf(media.get("target_type"));
        long actualTargetId = ((Number) media.get("target_id")).longValue();
        if (!normalizedTargetType.equals(actualTargetType) || targetId != actualTargetId) {
            throw new IllegalArgumentException("Target media does not belong to requested target.");
        }
        return binaryStorageService.read(String.valueOf(media.get("storage_path")));
    }

    public long requireTargetLocation(String targetType, long targetId) {
        String normalizedTargetType = normalizeTargetType(targetType);
        Long locationId = moderationRepository.locationForTarget(normalizedTargetType, targetId);
        if (locationId == null) {
            throw new IllegalArgumentException("Target content not found for supported target type.");
        }
        return locationId;
    }

    public void assertTargetOwner(String targetType, long targetId, long actorUserId) {
        String normalizedTargetType = normalizeTargetType(targetType);
        Long ownerUserId = moderationRepository.authorForTarget(normalizedTargetType, targetId);
        if (ownerUserId == null) {
            throw new IllegalArgumentException("Target content not found for supported target type.");
        }
        if (ownerUserId.longValue() != actorUserId) {
            throw new org.springframework.security.access.AccessDeniedException("Target media access is limited to the content owner.");
        }
    }

    private void assertTargetLocation(String targetType, long targetId, long locationId) {
        Long targetLocation = moderationRepository.locationForTarget(targetType, targetId);
        if (targetLocation == null) {
            throw new IllegalArgumentException("Target content not found for supported target type.");
        }
        if (!targetLocation.equals(locationId)) {
            throw new IllegalArgumentException("Target content does not belong to the requested location.");
        }
    }

    private List<Map<String, Object>> detectSensitiveHits(String contentText) {
        List<Map<String, Object>> dictionary = moderationRepository.activeSensitiveEntries();
        List<Map<String, Object>> hits = new ArrayList<>();
        String normalized = normalize(contentText);
        for (Map<String, Object> entry : dictionary) {
            String term = String.valueOf(entry.get("normalized_term"));
            if (normalized.contains(term)) {
                hits.add(Map.of(
                    "matchedTerm", term,
                    "policyRuleId", entry.get("rule_id"),
                    "policyRuleCode", entry.get("rule_code"),
                    "tag", entry.get("tag"),
                    "severity", entry.get("severity"),
                    "confidence", 1.0,
                    "timestamp", LocalDateTime.now().toString(),
                    "snippet", snippet(contentText, term)
                ));
            }
        }
        return hits;
    }

    private void autoPreScreen(long locationId, String targetType, long targetId, String contentText) {
        List<Map<String, Object>> hits = detectSensitiveHits(contentText);
        if (hits.isEmpty()) {
            return;
        }
        long caseId = moderationRepository.createCase(locationId, targetType, targetId, hits);
        auditLogService.logSystemEvent(
            "MODERATION_PRE_SCREEN_CASE_CREATED",
            "moderation_case",
            String.valueOf(caseId),
            locationId,
            Map.of("targetType", targetType, "targetId", targetId)
        );
    }

    private String normalizeTargetType(String targetType) {
        String normalized = targetType == null ? "" : targetType.trim().toUpperCase(Locale.ROOT);
        if (!List.of("POST", "COMMENT", "QNA").contains(normalized)) {
            throw new IllegalArgumentException("Target type must be one of POST, COMMENT, or QNA.");
        }
        return normalized;
    }

    private String extension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "bin";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    private void enforcePostingAllowed(long authorUserId,
                                       long locationId,
                                       String targetType,
                                       Long parentTargetId) {
        if (!moderationRepository.hasActiveSuspension(authorUserId)) {
            return;
        }

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("targetType", targetType);
        if (parentTargetId != null) {
            payload.put("parentTargetId", parentTargetId);
        }
        payload.put("reason", "active_suspension");

        auditLogService.logUserEvent(
            authorUserId,
            "SUSPENDED_CONTENT_POST_BLOCKED",
            "community_target",
            parentTargetId == null ? "NEW" : String.valueOf(parentTargetId),
            locationId,
            payload
        );
        throw new AccessDeniedException("User is currently suspended from community posting.");
    }
}
