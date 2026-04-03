package com.lexibridge.operations.modules.moderation.api;

import com.lexibridge.operations.modules.moderation.model.ModerationCaseCommand;
import com.lexibridge.operations.modules.moderation.service.ModerationService;
import com.lexibridge.operations.security.service.AuthorizationScopeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.List;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/moderation")
public class ModerationApiController {

    private final ModerationService moderationService;
    private final AuthorizationScopeService authorizationScopeService;

    public ModerationApiController(ModerationService moderationService,
                                   AuthorizationScopeService authorizationScopeService) {
        this.moderationService = moderationService;
        this.authorizationScopeService = authorizationScopeService;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")
    public Map<String, Object> summary() {
        boolean admin = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (admin) {
            return moderationService.dashboardSummary();
        }
        Long locationId = authorizationScopeService.currentLocationScope()
            .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("Location scope is required."));
        return moderationService.dashboardSummary(locationId);
    }

    @PostMapping("/targets/posts")
    @PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")
    public Map<String, Object> createPostTarget(@Valid @RequestBody CreatePostTargetCommand command) {
        authorizationScopeService.assertLocationAccess(command.locationId);
        authorizationScopeService.assertActorUser(command.authorUserId);
        long targetId = moderationService.createPostTarget(command.locationId, command.authorUserId, command.title, command.bodyHtml);
        return Map.of("targetType", "POST", "targetId", targetId, "status", "PUBLISHED");
    }

    @PostMapping("/community/posts")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> createCommunityPostTarget(@Valid @RequestBody CreateOwnPostTargetCommand command) {
        authorizationScopeService.assertLocationAccess(command.locationId);
        long authorUserId = authorizationScopeService.requireCurrentUserId();
        long targetId = moderationService.createPostTarget(command.locationId, authorUserId, command.title, command.bodyHtml);
        return Map.of("targetType", "POST", "targetId", targetId, "status", "PUBLISHED");
    }

    @PostMapping("/targets/comments")
    @PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")
    public Map<String, Object> createCommentTarget(@Valid @RequestBody CreateCommentTargetCommand command) {
        authorizationScopeService.assertLocationAccess(command.locationId);
        authorizationScopeService.assertActorUser(command.authorUserId);
        long targetId = moderationService.createCommentTarget(command.locationId, command.postId, command.authorUserId, command.bodyText);
        return Map.of("targetType", "COMMENT", "targetId", targetId, "status", "PUBLISHED");
    }

    @PostMapping("/community/comments")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> createCommunityCommentTarget(@Valid @RequestBody CreateOwnCommentTargetCommand command) {
        authorizationScopeService.assertLocationAccess(command.locationId);
        long authorUserId = authorizationScopeService.requireCurrentUserId();
        long targetId = moderationService.createCommentTarget(command.locationId, command.postId, authorUserId, command.bodyText);
        return Map.of("targetType", "COMMENT", "targetId", targetId, "status", "PUBLISHED");
    }

    @PostMapping("/targets/qna")
    @PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")
    public Map<String, Object> createQnaTarget(@Valid @RequestBody CreateQnaTargetCommand command) {
        authorizationScopeService.assertLocationAccess(command.locationId);
        authorizationScopeService.assertActorUser(command.authorUserId);
        long targetId = moderationService.createQnaTarget(command.locationId, command.authorUserId, command.questionText, command.answerText);
        return Map.of("targetType", "QNA", "targetId", targetId, "status", "PUBLISHED");
    }

    @PostMapping("/community/qna")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> createCommunityQnaTarget(@Valid @RequestBody CreateOwnQnaTargetCommand command) {
        authorizationScopeService.assertLocationAccess(command.locationId);
        long authorUserId = authorizationScopeService.requireCurrentUserId();
        long targetId = moderationService.createQnaTarget(command.locationId, authorUserId, command.questionText, command.answerText);
        return Map.of("targetType", "QNA", "targetId", targetId, "status", "PUBLISHED");
    }

    @PostMapping("/cases")
    @PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")
    public Map<String, Object> createCase(@Valid @RequestBody CreateModerationCaseCommand command) {
        authorizationScopeService.assertLocationAccess(command.locationId);
        long caseId = moderationService.createCase(new ModerationCaseCommand(
            command.locationId,
            command.targetType,
            command.targetId,
            command.contentText
        ));
        return Map.of("caseId", caseId, "status", "PENDING");
    }

    @PostMapping("/cases/{caseId}/resolve")
    @PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")
    public Map<String, Object> resolveCase(@PathVariable long caseId,
                                           @Valid @RequestBody ResolveModerationCaseCommand command) {
        authorizationScopeService.assertModerationCaseScope(caseId);
        authorizationScopeService.assertActorUser(command.reviewerUserId);
        return moderationService.resolveCase(
            caseId,
            command.decision,
            command.reviewerUserId,
            command.offenderUserId,
            command.reason,
            command.appealNote
        );
    }

    @PostMapping("/reports")
    public Map<String, Object> submitReport(@Valid @RequestBody SubmitUserReportCommand command) {
        authorizationScopeService.assertLocationAccess(command.locationId);
        authorizationScopeService.assertActorUser(command.reporterUserId);
        return moderationService.submitUserReport(
            command.reporterUserId,
            command.locationId,
            command.targetType,
            command.targetId,
            command.reasonText
        );
    }

    @PostMapping("/reports/{reportId}/resolve")
    @PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")
    public Map<String, Object> resolveReport(@PathVariable long reportId,
                                             @Valid @RequestBody ResolveUserReportCommand command) {
        authorizationScopeService.assertUserReportScope(reportId);
        authorizationScopeService.assertActorUser(command.moderatorUserId);
        return moderationService.resolveUserReport(
            reportId,
            command.disposition,
            command.moderatorUserId,
            command.resolutionNote,
            command.penaltySummary
        );
    }

    @GetMapping("/reports/by-reporter/{reporterUserId}")
    public java.util.List<Map<String, Object>> reportsByReporter(@PathVariable long reporterUserId) {
        authorizationScopeService.assertActorUser(reporterUserId);
        return moderationService.reportsByReporter(reporterUserId);
    }

    @GetMapping("/penalties/{userId}")
    public java.util.List<Map<String, Object>> penalties(@PathVariable long userId) {
        authorizationScopeService.assertActorUser(userId);
        return moderationService.penaltiesForUser(userId);
    }

    @PostMapping("/cases/{caseId}/media")
    @PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")
    public Map<String, Object> uploadCaseMedia(@PathVariable long caseId,
                                               @RequestParam("file") MultipartFile file) throws IOException {
        authorizationScopeService.assertModerationCaseScope(caseId);
        return moderationService.addCaseMedia(
            caseId,
            file.getOriginalFilename() == null ? "moderation-media.bin" : file.getOriginalFilename(),
            file.getBytes(),
            authorizationScopeService.requireCurrentUserId()
        );
    }

    @GetMapping("/cases/{caseId}/media")
    @PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")
    public List<Map<String, Object>> listCaseMedia(@PathVariable long caseId) {
        authorizationScopeService.assertModerationCaseScope(caseId);
        return moderationService.caseMedia(caseId);
    }

    @GetMapping("/cases/{caseId}/media/{mediaId}/download")
    @PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")
    public ResponseEntity<byte[]> downloadCaseMedia(@PathVariable long caseId,
                                                    @PathVariable long mediaId) {
        authorizationScopeService.assertModerationCaseScope(caseId);
        var binary = moderationService.downloadCaseMedia(caseId, mediaId);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(binary.mimeType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=moderation-media-" + mediaId)
            .body(binary.payload());
    }

    @PostMapping("/targets/{targetType}/{targetId}/media")
    @PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")
    public Map<String, Object> uploadTargetMedia(@PathVariable String targetType,
                                                  @PathVariable long targetId,
                                                  @RequestParam("file") MultipartFile file) throws IOException {
        long locationId = moderationService.requireTargetLocation(targetType, targetId);
        authorizationScopeService.assertLocationAccess(locationId);
        return moderationService.addTargetMedia(
            targetType,
            targetId,
            safeFilename(file, "target-media.bin"),
            file.getBytes(),
            authorizationScopeService.requireCurrentUserId()
        );
    }

    @GetMapping("/targets/{targetType}/{targetId}/media")
    @PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")
    public List<Map<String, Object>> listTargetMedia(@PathVariable String targetType,
                                                      @PathVariable long targetId) {
        long locationId = moderationService.requireTargetLocation(targetType, targetId);
        authorizationScopeService.assertLocationAccess(locationId);
        return moderationService.targetMedia(targetType, targetId);
    }

    @GetMapping("/targets/{targetType}/{targetId}/media/{mediaId}/download")
    @PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")
    public ResponseEntity<byte[]> downloadTargetMedia(@PathVariable String targetType,
                                                       @PathVariable long targetId,
                                                       @PathVariable long mediaId) {
        long locationId = moderationService.requireTargetLocation(targetType, targetId);
        authorizationScopeService.assertLocationAccess(locationId);
        var binary = moderationService.downloadTargetMedia(targetType, targetId, mediaId);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(binary.mimeType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=target-media-" + mediaId)
            .body(binary.payload());
    }

    @PostMapping("/community/posts/{postId}/media")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> uploadOwnPostMedia(@PathVariable long postId,
                                                   @RequestParam("file") MultipartFile file) throws IOException {
        return uploadOwnTargetMedia("POST", postId, file, "post-media.bin");
    }

    @GetMapping("/community/posts/{postId}/media")
    @PreAuthorize("isAuthenticated()")
    public List<Map<String, Object>> listOwnPostMedia(@PathVariable long postId) {
        return listOwnTargetMedia("POST", postId);
    }

    @GetMapping("/community/posts/{postId}/media/{mediaId}/download")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> downloadOwnPostMedia(@PathVariable long postId,
                                                        @PathVariable long mediaId) {
        return downloadOwnTargetMedia("POST", postId, mediaId);
    }

    @PostMapping("/community/comments/{commentId}/media")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> uploadOwnCommentMedia(@PathVariable long commentId,
                                                      @RequestParam("file") MultipartFile file) throws IOException {
        return uploadOwnTargetMedia("COMMENT", commentId, file, "comment-media.bin");
    }

    @GetMapping("/community/comments/{commentId}/media")
    @PreAuthorize("isAuthenticated()")
    public List<Map<String, Object>> listOwnCommentMedia(@PathVariable long commentId) {
        return listOwnTargetMedia("COMMENT", commentId);
    }

    @GetMapping("/community/comments/{commentId}/media/{mediaId}/download")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> downloadOwnCommentMedia(@PathVariable long commentId,
                                                           @PathVariable long mediaId) {
        return downloadOwnTargetMedia("COMMENT", commentId, mediaId);
    }

    @PostMapping("/community/qna/{qnaId}/media")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> uploadOwnQnaMedia(@PathVariable long qnaId,
                                                  @RequestParam("file") MultipartFile file) throws IOException {
        return uploadOwnTargetMedia("QNA", qnaId, file, "qna-media.bin");
    }

    @GetMapping("/community/qna/{qnaId}/media")
    @PreAuthorize("isAuthenticated()")
    public List<Map<String, Object>> listOwnQnaMedia(@PathVariable long qnaId) {
        return listOwnTargetMedia("QNA", qnaId);
    }

    @GetMapping("/community/qna/{qnaId}/media/{mediaId}/download")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> downloadOwnQnaMedia(@PathVariable long qnaId,
                                                       @PathVariable long mediaId) {
        return downloadOwnTargetMedia("QNA", qnaId, mediaId);
    }

    private Map<String, Object> uploadOwnTargetMedia(String targetType,
                                                     long targetId,
                                                     MultipartFile file,
                                                     String fallbackFilename) throws IOException {
        long actorUserId = authorizationScopeService.requireCurrentUserId();
        long locationId = moderationService.requireTargetLocation(targetType, targetId);
        authorizationScopeService.assertLocationAccess(locationId);
        moderationService.assertTargetOwner(targetType, targetId, actorUserId);
        return moderationService.addTargetMedia(targetType, targetId, safeFilename(file, fallbackFilename), file.getBytes(), actorUserId);
    }

    private List<Map<String, Object>> listOwnTargetMedia(String targetType, long targetId) {
        long actorUserId = authorizationScopeService.requireCurrentUserId();
        long locationId = moderationService.requireTargetLocation(targetType, targetId);
        authorizationScopeService.assertLocationAccess(locationId);
        moderationService.assertTargetOwner(targetType, targetId, actorUserId);
        return moderationService.targetMedia(targetType, targetId);
    }

    private ResponseEntity<byte[]> downloadOwnTargetMedia(String targetType, long targetId, long mediaId) {
        long actorUserId = authorizationScopeService.requireCurrentUserId();
        long locationId = moderationService.requireTargetLocation(targetType, targetId);
        authorizationScopeService.assertLocationAccess(locationId);
        moderationService.assertTargetOwner(targetType, targetId, actorUserId);
        var binary = moderationService.downloadTargetMedia(targetType, targetId, mediaId);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(binary.mimeType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=target-media-" + mediaId)
            .body(binary.payload());
    }

    private String safeFilename(MultipartFile file, String fallback) {
        if (file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()) {
            return fallback;
        }
        return file.getOriginalFilename();
    }

    public static class CreateModerationCaseCommand {
        @NotNull
        public Long locationId;
        @NotBlank
        public String targetType;
        @NotNull
        public Long targetId;
        @NotBlank
        public String contentText;
    }

    public static class CreatePostTargetCommand {
        @NotNull
        public Long locationId;
        @NotNull
        public Long authorUserId;
        public String title;
        @NotBlank
        public String bodyHtml;
    }

    public static class CreateCommentTargetCommand {
        @NotNull
        public Long locationId;
        @NotNull
        public Long postId;
        @NotNull
        public Long authorUserId;
        @NotBlank
        public String bodyText;
    }

    public static class CreateQnaTargetCommand {
        @NotNull
        public Long locationId;
        @NotNull
        public Long authorUserId;
        @NotBlank
        public String questionText;
        public String answerText;
    }

    public static class CreateOwnPostTargetCommand {
        @NotNull
        public Long locationId;
        public String title;
        @NotBlank
        public String bodyHtml;
    }

    public static class CreateOwnCommentTargetCommand {
        @NotNull
        public Long locationId;
        @NotNull
        public Long postId;
        @NotBlank
        public String bodyText;
    }

    public static class CreateOwnQnaTargetCommand {
        @NotNull
        public Long locationId;
        @NotBlank
        public String questionText;
        public String answerText;
    }

    public static class ResolveModerationCaseCommand {
        @NotBlank
        public String decision;
        @NotNull
        public Long reviewerUserId;
        @NotNull
        public Long offenderUserId;
        @NotBlank
        public String reason;
        public String appealNote;
    }

    public static class SubmitUserReportCommand {
        @NotNull
        public Long locationId;
        @NotNull
        public Long reporterUserId;
        @NotBlank
        public String targetType;
        @NotNull
        public Long targetId;
        @NotBlank
        public String reasonText;
    }

    public static class ResolveUserReportCommand {
        @NotBlank
        public String disposition;
        @NotNull
        public Long moderatorUserId;
        public String resolutionNote;
        public String penaltySummary;
    }
}
