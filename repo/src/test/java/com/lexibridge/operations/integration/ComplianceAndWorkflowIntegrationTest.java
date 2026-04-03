package com.lexibridge.operations.integration;

import com.lexibridge.operations.modules.booking.model.BookingRequest;
import com.lexibridge.operations.modules.booking.service.BookingService;
import com.lexibridge.operations.modules.content.model.ContentCreateRequest;
import com.lexibridge.operations.modules.content.service.ContentMediaService;
import com.lexibridge.operations.modules.content.service.ContentService;
import com.lexibridge.operations.modules.leave.model.LeaveRequestCommand;
import com.lexibridge.operations.modules.leave.service.LeaveService;
import com.lexibridge.operations.modules.moderation.service.ModerationService;
import com.lexibridge.operations.modules.admin.service.AdminUserManagementService;
import com.lexibridge.operations.modules.admin.service.WebhookSecurityService;
import com.lexibridge.operations.security.service.DatabaseUserDetailsService;
import com.lexibridge.operations.security.service.AuthorizationScopeService;
import com.lexibridge.operations.security.privacy.FieldEncryptionService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
@SpringBootTest(properties = {
    "spring.task.scheduling.enabled=false",
    "lexibridge.security.antivirus.enabled=false"
})
class ComplianceAndWorkflowIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("lexibridge")
        .withUsername("lexibridge")
        .withPassword("lexibridge")
        .withCommand("--log-bin-trust-function-creators=1");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired
    private BookingService bookingService;
    @Autowired
    private LeaveService leaveService;
    @Autowired
    private ContentService contentService;
    @Autowired
    private ContentMediaService contentMediaService;
    @Autowired
    private ModerationService moderationService;
    @Autowired
    private WebhookSecurityService webhookSecurityService;
    @Autowired
    private AdminUserManagementService adminUserManagementService;
    @Autowired
    private DatabaseUserDetailsService databaseUserDetailsService;
    @Autowired
    private FieldEncryptionService fieldEncryptionService;
    @Autowired
    private AuthorizationScopeService authorizationScopeService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void bookingReserve_shouldStoreEncryptedPiiColumns() {
        long userId = ensureUser("compliance-booking");
        Map<String, Object> result = bookingService.reserve(new BookingRequest(
            1L,
            userId,
            "Sensitive Person",
            "+1 (555) 2233",
            LocalDateTime.now().plusHours(4).withMinute(0).withSecond(0).withNano(0),
            30,
            "compliance-pii"
        ));

        Long bookingId = (Long) result.get("bookingId");
        Map<String, Object> row = jdbcTemplate.queryForMap(
            "select customer_name, customer_phone, customer_name_enc, customer_phone_enc from booking_order where id = ?",
            bookingId
        );

        assertEquals("ENCRYPTED", row.get("customer_name"));
        assertEquals("ENCRYPTED", row.get("customer_phone"));
        assertNotNull(row.get("customer_name_enc"));
        assertNotNull(row.get("customer_phone_enc"));
        assertEquals("Sensitive Person", fieldEncryptionService.decryptString((String) row.get("customer_name_enc")));
    }

    @Test
    void leaveWorkflow_shouldSubmitAndApproveTask() {
        long requesterId = ensureUser("leave-requester");
        long approverId = ensureUser("leave-approver");

        jdbcTemplate.update(
            "insert into approval_rule (location_id, priority, leave_type, approver_role_code, approver_user_id, is_active) values (1, 1, 'ANNUAL_LEAVE', 'MANAGER', ?, true)",
            approverId
        );

        Map<String, Object> submitResult = leaveService.submit(new LeaveRequestCommand(
            1L,
            requesterId,
            "ANNUAL_LEAVE",
            LocalDate.now().plusDays(2),
            LocalDate.now().plusDays(2),
            480,
            null,
            Map.of()
        ));

        long leaveRequestId = ((Number) submitResult.get("leaveRequestId")).longValue();
        Long taskId = jdbcTemplate.queryForObject(
            "select id from approval_task where leave_request_id = ? limit 1",
            Long.class,
            leaveRequestId
        );
        assertNotNull(taskId);

        Map<String, Object> approveResult = leaveService.approveTask(taskId, approverId, "integration approve");
        assertEquals("APPROVED", approveResult.get("status"));

        String requestStatus = jdbcTemplate.queryForObject(
            "select status from leave_request where id = ?",
            String.class,
            leaveRequestId
        );
        assertEquals("APPROVED", requestStatus);
    }

    @Test
    void leaveWorkflow_shouldSupportRequesterCorrectionResubmission() {
        long requesterId = ensureUser("leave-correct-requester");
        long approverId = ensureUser("leave-correct-approver");

        jdbcTemplate.update(
            "insert into approval_rule (location_id, priority, leave_type, approver_role_code, approver_user_id, is_active) values (1, 1, 'ANNUAL_LEAVE', 'MANAGER', ?, true)",
            approverId
        );

        Map<String, Object> submitResult = leaveService.submit(new LeaveRequestCommand(
            1L,
            requesterId,
            "ANNUAL_LEAVE",
            LocalDate.now().plusDays(3),
            LocalDate.now().plusDays(3),
            480,
            null,
            Map.of("reason", "initial")
        ));
        long leaveRequestId = ((Number) submitResult.get("leaveRequestId")).longValue();
        Long firstTaskId = jdbcTemplate.queryForObject(
            "select id from approval_task where leave_request_id = ? order by id asc limit 1",
            Long.class,
            leaveRequestId
        );
        assertNotNull(firstTaskId);

        leaveService.requestCorrection(firstTaskId, approverId, "Please update details");

        String correctionStatus = jdbcTemplate.queryForObject(
            "select status from leave_request where id = ?",
            String.class,
            leaveRequestId
        );
        assertEquals("NEEDS_CORRECTION", correctionStatus);

        leaveService.resubmitCorrection(
            leaveRequestId,
            new LeaveRequestCommand(
                1L,
                requesterId,
                "ANNUAL_LEAVE",
                LocalDate.now().plusDays(4),
                LocalDate.now().plusDays(4),
                240,
                null,
                Map.of("reason", "corrected")
            )
        );

        String resubmittedStatus = jdbcTemplate.queryForObject(
            "select status from leave_request where id = ?",
            String.class,
            leaveRequestId
        );
        assertEquals("PENDING_APPROVAL", resubmittedStatus);

        Integer approvalTasks = jdbcTemplate.queryForObject(
            "select count(*) from approval_task where leave_request_id = ?",
            Integer.class,
            leaveRequestId
        );
        assertEquals(2, approvalTasks);
    }

    @Test
    void adminWebhook_shouldRegisterPrivateCallbackAndList() {
        long webhookId = webhookSecurityService.register(1L, "itest-webhook", "http://127.0.0.1:8080/internal/hook");
        assertTrue(webhookSecurityService.canDeliver(webhookId));
        assertTrue(webhookSecurityService.activeWebhooks().stream().anyMatch(w -> ((Number) w.get("id")).longValue() == webhookId));
    }

    @Test
    void adminUserChanges_shouldEnforceImmediately() {
        long actorId = ensureUser("admin-actor");
        String username = "managed-" + UUID.randomUUID().toString().substring(0, 8);

        Map<String, Object> create = adminUserManagementService.createUser(
            1L,
            username,
            "Managed User",
            "managed@example.com",
            "StrongPass123!@",
            true,
            List.of("EMPLOYEE"),
            actorId
        );
        long managedUserId = ((Number) create.get("userId")).longValue();

        assertNotNull(databaseUserDetailsService.loadUserByUsername(username));

        adminUserManagementService.assignRole(managedUserId, "MANAGER", actorId);
        var loadedWithRoles = databaseUserDetailsService.loadUserByUsername(username);
        assertTrue(loadedWithRoles.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_MANAGER")));

        adminUserManagementService.updateUser(managedUserId, 1L, "Managed User", "managed@example.com", false, actorId);
        assertThrows(UsernameNotFoundException.class, () -> databaseUserDetailsService.loadUserByUsername(username));
    }

    @Test
    void noShowOverride_shouldExcludeBookingFromAutoCloseSweep() {
        long userId = ensureUser("noshow-override");
        jdbcTemplate.update(
            """
            insert into booking_order
            (location_id, customer_name, customer_phone, start_at, end_at, slot_count, status, order_note, no_show_close_at, no_show_auto_close_disabled, created_by)
            values (1, 'User A', '5551000', date_sub(current_timestamp, interval 2 hour), date_sub(current_timestamp, interval 90 minute), 2, 'CONFIRMED', 'noshow-a', date_sub(current_timestamp, interval 15 minute), true, ?)
            """,
            userId
        );
        jdbcTemplate.update(
            """
            insert into booking_order
            (location_id, customer_name, customer_phone, start_at, end_at, slot_count, status, order_note, no_show_close_at, no_show_auto_close_disabled, created_by)
            values (1, 'User B', '5552000', date_sub(current_timestamp, interval 2 hour), date_sub(current_timestamp, interval 90 minute), 2, 'CONFIRMED', 'noshow-b', date_sub(current_timestamp, interval 15 minute), false, ?)
            """,
            userId
        );

        int closed = bookingService.autoCloseNoShows();
        assertEquals(1, closed);

        String disabledStatus = jdbcTemplate.queryForObject(
            "select status from booking_order where order_note = 'noshow-a'",
            String.class
        );
        String enabledStatus = jdbcTemplate.queryForObject(
            "select status from booking_order where order_note = 'noshow-b'",
            String.class
        );
        assertEquals("CONFIRMED", disabledStatus);
        assertEquals("COMPLETED", enabledStatus);
    }

    @Test
    void binaryStorageRoundTrip_shouldPersistAndRetrieveForAllUploadDomains() {
        ensureLocation(2L);
        long userOne = ensureUser("binary-one", 1L);
        long userTwo = ensureUser("binary-two", 2L);

        long contentItemId = contentService.createDraft(new ContentCreateRequest(
            1L,
            userOne,
            "alpha-" + UUID.randomUUID(),
            null,
            "GENERAL",
            null,
            "phrase",
            "example",
            "definition",
            Map.of()
        )).contentItemId();
        byte[] png = pngBytes();
        Map<String, Object> contentUpload = contentMediaService.upload(contentItemId, "content.png", png, userOne);
        long contentMediaId = ((Number) firstRowId("content_media", "content_item_id", contentItemId)).longValue();
        byte[] contentDownloaded = contentMediaService.download(contentItemId, contentMediaId).payload();
        assertTrue(java.util.Arrays.equals(png, contentDownloaded));
        assertEquals(contentUpload.get("checksum"), sha256Hex(contentDownloaded));

        long bookingId = createBookingRow(1L, userOne, "binary-booking");
        byte[] jpeg = jpegBytes();
        bookingService.addAttachment(bookingId, "booking.jpg", jpeg, userOne);
        long bookingAttachmentId = ((Number) firstRowId("booking_attachment", "booking_order_id", bookingId)).longValue();
        byte[] bookingDownloaded = bookingService.downloadAttachment(bookingId, bookingAttachmentId).payload();
        assertTrue(java.util.Arrays.equals(jpeg, bookingDownloaded));

        long leaveRequestId = createLeaveRequestRow(1L, userOne);
        byte[] leavePng = pngBytesVariant();
        leaveService.addAttachment(leaveRequestId, "leave.png", leavePng, userOne);
        long leaveAttachmentId = ((Number) firstRowId("leave_request_attachment", "leave_request_id", leaveRequestId)).longValue();
        byte[] leaveDownloaded = leaveService.downloadAttachment(leaveRequestId, leaveAttachmentId).payload();
        assertTrue(java.util.Arrays.equals(leavePng, leaveDownloaded));

        long targetPostId = moderationService.createPostTarget(2L, userTwo, "binary-post", "body");
        long caseId = moderationService.createCase(new com.lexibridge.operations.modules.moderation.model.ModerationCaseCommand(
            2L,
            "POST",
            targetPostId,
            "clean content"
        ));
        byte[] moderationPng = pngBytesThirdVariant();
        moderationService.addCaseMedia(caseId, "mod.png", moderationPng, userTwo);
        long moderationMediaId = ((Number) firstRowId("moderation_case_media", "case_id", caseId)).longValue();
        byte[] moderationDownloaded = moderationService.downloadCaseMedia(caseId, moderationMediaId).payload();
        assertTrue(java.util.Arrays.equals(moderationPng, moderationDownloaded));
    }

    @Test
    void moderationSummary_shouldIsolateByLocationWithExactCounts() {
        ensureLocation(2L);
        Map<String, Object> loc1Before = moderationService.dashboardSummary(1L);
        Map<String, Object> loc2Before = moderationService.dashboardSummary(2L);
        Map<String, Object> globalBefore = moderationService.dashboardSummary();

        jdbcTemplate.update(
            "insert into moderation_case (location_id, target_type, target_id, status, sensitive_hits_json) values (1, 'POST', 1001, 'PENDING', cast('[]' as json))"
        );
        jdbcTemplate.update(
            "insert into moderation_case (location_id, target_type, target_id, status, sensitive_hits_json, resolved_at) values (1, 'POST', 1002, 'APPROVED', cast('[]' as json), current_timestamp)"
        );
        jdbcTemplate.update(
            "insert into moderation_case (location_id, target_type, target_id, status, sensitive_hits_json, resolved_at) values (2, 'POST', 2001, 'REJECTED', cast('[]' as json), current_timestamp)"
        );

        Map<String, Object> loc1 = moderationService.dashboardSummary(1L);
        Map<String, Object> loc2 = moderationService.dashboardSummary(2L);
        Map<String, Object> global = moderationService.dashboardSummary();

        assertEquals(((Number) loc1Before.get("pendingCount")).intValue() + 1, ((Number) loc1.get("pendingCount")).intValue());
        assertEquals(((Number) loc1Before.get("approvedToday")).intValue() + 1, ((Number) loc1.get("approvedToday")).intValue());
        assertEquals(((Number) loc1Before.get("rejectedToday")).intValue(), ((Number) loc1.get("rejectedToday")).intValue());

        assertEquals(((Number) loc2Before.get("pendingCount")).intValue(), ((Number) loc2.get("pendingCount")).intValue());
        assertEquals(((Number) loc2Before.get("approvedToday")).intValue(), ((Number) loc2.get("approvedToday")).intValue());
        assertEquals(((Number) loc2Before.get("rejectedToday")).intValue() + 1, ((Number) loc2.get("rejectedToday")).intValue());

        assertEquals(((Number) globalBefore.get("pendingCount")).intValue() + 1, ((Number) global.get("pendingCount")).intValue());
        assertEquals(((Number) globalBefore.get("approvedToday")).intValue() + 1, ((Number) global.get("approvedToday")).intValue());
        assertEquals(((Number) globalBefore.get("rejectedToday")).intValue() + 1, ((Number) global.get("rejectedToday")).intValue());
    }

    @Test
    void moderationTargetCreate_shouldAutoScreenAndCreateCaseOnSensitiveHit() {
        long moderatorId = ensureUser("moderation-pre-screen", 1L);
        long ruleId = ensurePolicyRule("AUTO_DICT_RULE", "HIGH");
        ensureSensitiveTerm("autoscreenterm", "ABUSE", ruleId);

        int before = jdbcTemplate.queryForObject("select count(*) from moderation_case", Integer.class);
        long postId = moderationService.createPostTarget(1L, moderatorId, "title", "contains autoscreenterm for screening");
        assertTrue(postId > 0);
        int after = jdbcTemplate.queryForObject("select count(*) from moderation_case", Integer.class);
        assertEquals(before + 1, after);
    }

    @Test
    void moderationCaseViews_shouldIncludeTargetArtifactFieldsAndMediaCount() {
        long authorId = ensureUser("moderation-artifact-author", 1L);
        String title = "artifact-title-" + UUID.randomUUID().toString().substring(0, 8);
        String body = "artifact-body-html";

        long postId = moderationService.createPostTarget(1L, authorId, title, body);
        long caseId = moderationService.createCase(new com.lexibridge.operations.modules.moderation.model.ModerationCaseCommand(
            1L,
            "POST",
            postId,
            "manually flagged"
        ));

        moderationService.addTargetMedia("POST", postId, "target.png", uniquePngBytes(), authorId);
        moderationService.addCaseMedia(caseId, "artifact.png", pngBytesFourthVariant(), authorId);

        Map<String, Object> row = moderationService.recentCases(1L, 100).stream()
            .filter(candidate -> ((Number) candidate.get("id")).longValue() == caseId)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Moderation case not found in recent case list"));

        assertEquals(title, row.get("target_title"));
        assertEquals(body, row.get("target_body"));
        assertEquals(authorId, ((Number) row.get("target_author_user_id")).longValue());
        assertEquals("PUBLISHED", row.get("target_status"));
        assertEquals(1, ((Number) row.get("case_media_count")).intValue());
        assertEquals(1, ((Number) row.get("target_media_count")).intValue());

        Map<String, Object> detail = moderationService.caseDetails(caseId);
        assertEquals(title, detail.get("target_title"));
        assertEquals(body, detail.get("target_body"));
        assertEquals(authorId, ((Number) detail.get("target_author_user_id")).longValue());
        assertEquals("PUBLISHED", detail.get("target_status"));
        assertEquals(1, ((Number) detail.get("case_media_count")).intValue());
        assertEquals(1, ((Number) detail.get("target_media_count")).intValue());
    }

    @Test
    void moderationTargetMediaLifecycle_shouldSupportPostCommentAndQna() {
        long authorId = ensureUser("target-media-author", 1L);

        long postId = moderationService.createPostTarget(1L, authorId, "post-with-media", "body");
        byte[] postBytes = uniquePngBytes();
        moderationService.addTargetMedia("POST", postId, "post.png", postBytes, authorId);
        long postMediaId = ((Number) jdbcTemplate.queryForObject(
            "select id from community_target_media where target_type = 'POST' and target_id = ? order by id desc limit 1",
            Long.class,
            postId
        )).longValue();
        byte[] postDownloaded = moderationService.downloadTargetMedia("POST", postId, postMediaId).payload();
        assertTrue(java.util.Arrays.equals(postBytes, postDownloaded));

        long commentId = moderationService.createCommentTarget(1L, postId, authorId, "comment body");
        byte[] commentBytes = uniquePngBytes();
        moderationService.addTargetMedia("COMMENT", commentId, "comment.png", commentBytes, authorId);
        long commentMediaId = ((Number) jdbcTemplate.queryForObject(
            "select id from community_target_media where target_type = 'COMMENT' and target_id = ? order by id desc limit 1",
            Long.class,
            commentId
        )).longValue();
        byte[] commentDownloaded = moderationService.downloadTargetMedia("COMMENT", commentId, commentMediaId).payload();
        assertTrue(java.util.Arrays.equals(commentBytes, commentDownloaded));

        long qnaId = moderationService.createQnaTarget(1L, authorId, "question", "answer");
        byte[] qnaBytes = uniquePngBytes();
        moderationService.addTargetMedia("QNA", qnaId, "qna.png", qnaBytes, authorId);
        long qnaMediaId = ((Number) jdbcTemplate.queryForObject(
            "select id from community_target_media where target_type = 'QNA' and target_id = ? order by id desc limit 1",
            Long.class,
            qnaId
        )).longValue();
        byte[] qnaDownloaded = moderationService.downloadTargetMedia("QNA", qnaId, qnaMediaId).payload();
        assertTrue(java.util.Arrays.equals(qnaBytes, qnaDownloaded));
    }

    @Test
    void moderationPosting_shouldRejectUserWithActiveSuspension() {
        long suspendedAuthorId = ensureUser("suspended-author", 1L);
        String blockedTitle = "blocked-post-" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update(
            """
            insert into user_penalty (user_id, penalty_type, start_at, end_at, reason_text, appeal_note, created_by)
            values (?, 'SUSPENSION_30_DAYS', date_sub(current_timestamp, interval 1 day), date_add(current_timestamp, interval 7 day), 'active suspension', 'integration test', ?)
            """,
            suspendedAuthorId,
            suspendedAuthorId
        );

        assertThrows(
            org.springframework.security.access.AccessDeniedException.class,
            () -> moderationService.createPostTarget(1L, suspendedAuthorId, blockedTitle, "blocked body")
        );

        Integer postCount = jdbcTemplate.queryForObject(
            "select count(*) from community_post where author_user_id = ? and title = ?",
            Integer.class,
            suspendedAuthorId,
            blockedTitle
        );
        assertEquals(0, postCount);

        Integer blockedAuditCount = jdbcTemplate.queryForObject(
            "select count(*) from audit_log where actor_user_id = ? and event_type = 'SUSPENDED_CONTENT_POST_BLOCKED'",
            Integer.class,
            suspendedAuthorId
        );
        assertTrue(blockedAuditCount != null && blockedAuditCount > 0);
    }

    @Test
    void leaveAttachmentReadAccess_shouldAllowActiveApproverAndAdmin() {
        long requesterId = ensureUser("leave-read-requester", 1L);
        long approverId = ensureUser("leave-read-approver", 1L);
        long leaveRequestId = createLeaveRequestRow(1L, requesterId);
        jdbcTemplate.update(
            "insert into approval_task (leave_request_id, approver_user_id, status, due_at) values (?, ?, 'PENDING', date_add(current_timestamp, interval 1 day))",
            leaveRequestId,
            approverId
        );

        String approverUsername = jdbcTemplate.queryForObject("select username from app_user where id = ?", String.class, approverId);
        if (approverUsername == null) {
            throw new IllegalStateException("Approver username not found");
        }

        String adminUsername = "leave-read-admin-" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update(
            "insert into app_user (location_id, username, full_name, password_hash, is_active) values (1, ?, 'Integration Admin', 'x', true)",
            adminUsername
        );

        try {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                approverUsername,
                "N/A",
                List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))
            ));
            assertDoesNotThrow(() -> authorizationScopeService.assertLeaveRequestReadAccess(leaveRequestId));

            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                adminUsername,
                "N/A",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
            ));
            assertDoesNotThrow(() -> authorizationScopeService.assertLeaveRequestReadAccess(leaveRequestId));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void duplicateChecksumValidation_shouldRejectCrossDomainReuseForLeaveAndModeration() {
        long actorId = ensureUser("checksum-reuse", 1L);
        byte[] uniquePng = uniquePngBytes();

        long contentItemId = contentService.createDraft(new ContentCreateRequest(
            1L,
            actorId,
            "dedupe-" + UUID.randomUUID(),
            null,
            "GENERAL",
            null,
            "phrase",
            "example",
            "definition",
            Map.of()
        )).contentItemId();
        contentMediaService.upload(contentItemId, "dedupe.png", uniquePng, actorId);

        long leaveRequestId = createLeaveRequestRow(1L, actorId);
        IllegalArgumentException leaveError = assertThrows(
            IllegalArgumentException.class,
            () -> leaveService.addAttachment(leaveRequestId, "dedupe-leave.png", uniquePng, actorId)
        );
        assertTrue(leaveError.getMessage().contains("Duplicate file checksum"));

        long postId = moderationService.createPostTarget(1L, actorId, "dedupe-post", "dedupe body");
        long caseId = moderationService.createCase(new com.lexibridge.operations.modules.moderation.model.ModerationCaseCommand(
            1L,
            "POST",
            postId,
            "dedupe text"
        ));
        IllegalArgumentException moderationError = assertThrows(
            IllegalArgumentException.class,
            () -> moderationService.addCaseMedia(caseId, "dedupe-mod.png", uniquePng, actorId)
        );
        assertTrue(moderationError.getMessage().contains("Duplicate file checksum"));
    }

    private long ensureUser(String prefix) {
        return ensureUser(prefix, 1L);
    }

    private long ensureUser(String prefix, long locationId) {
        ensureLocation(locationId);
        String username = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update(
            "insert into app_user (location_id, username, full_name, password_hash, is_active) values (?, ?, ?, 'x', true)",
            locationId,
            username,
            "Integration " + prefix
        );
        Long userId = jdbcTemplate.queryForObject("select id from app_user where username = ?", Long.class, username);
        if (userId == null) {
            throw new IllegalStateException("Unable to create integration user");
        }
        return userId;
    }

    private long createBookingRow(long locationId, long createdBy, String marker) {
        jdbcTemplate.update(
            """
            insert into booking_order
            (location_id, customer_name, customer_phone, start_at, end_at, slot_count, status, order_note, created_by)
            values (?, 'binary-user', '5551010', current_timestamp, date_add(current_timestamp, interval 30 minute), 2, 'CONFIRMED', ?, ?)
            """,
            locationId,
            marker,
            createdBy
        );
        Long bookingId = jdbcTemplate.queryForObject("select id from booking_order where order_note = ? limit 1", Long.class, marker);
        if (bookingId == null) {
            throw new IllegalStateException("Booking row not created");
        }
        return bookingId;
    }

    private long createLeaveRequestRow(long locationId, long requesterUserId) {
        jdbcTemplate.update(
            """
            insert into leave_request
            (location_id, requester_user_id, leave_type, start_date, end_date, duration_minutes, status, current_step)
            values (?, ?, 'ANNUAL_LEAVE', current_date, current_date, 60, 'SUBMITTED', 'PENDING_MANAGER_OR_HR_APPROVAL')
            """,
            locationId,
            requesterUserId
        );
        Long requestId = jdbcTemplate.queryForObject(
            "select id from leave_request where requester_user_id = ? order by id desc limit 1",
            Long.class,
            requesterUserId
        );
        if (requestId == null) {
            throw new IllegalStateException("Leave request row not created");
        }
        return requestId;
    }

    private Object firstRowId(String table, String foreignKeyColumn, long foreignKeyValue) {
        return jdbcTemplate.queryForObject(
            "select id from " + table + " where " + foreignKeyColumn + " = ? order by id desc limit 1",
            Object.class,
            foreignKeyValue
        );
    }

    private byte[] pngBytes() {
        return Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO5Bz4kAAAAASUVORK5CYII=");
    }

    private byte[] pngBytesVariant() {
        return Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M/wHwAEAQH/2xVvGQAAAABJRU5ErkJggg==");
    }

    private byte[] pngBytesThirdVariant() {
        return Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADUlEQVR42mP8/5+hHgAGgwJ/lL2QxQAAAABJRU5ErkJggg==");
    }

    private byte[] pngBytesFourthVariant() {
        return Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAAAAAA6fptVAAAACklEQVR42mNk+A8AAwUBAO+XwscAAAAASUVORK5CYII=");
    }

    private byte[] jpegBytes() {
        return Base64.getDecoder().decode("/9j/4AAQSkZJRgABAQAAAQABAAD/2wCEAAkGBxAQEBAPEA8QDw8QDw8PDw8QEA8QDw8PFREWFhURFRUYHSggGBolGxUVITEhJSkrLi4uFx8zODMsNygtLisBCgoKDg0OGhAQGi0lICUtLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLf/AABEIAAEAAQMBIgACEQEDEQH/xAAXAAADAQAAAAAAAAAAAAAAAAAAAQID/8QAFhEBAQEAAAAAAAAAAAAAAAAAAAER/9oADAMBAAIQAxAAAAGjAKf/xAAZEAEAAwEBAAAAAAAAAAAAAAABAAIRITH/2gAIAQEAAQUClHc4m3//xAAWEQEBAQAAAAAAAAAAAAAAAAABABH/2gAIAQMBAT8BKf/EABYRAQEBAAAAAAAAAAAAAAAAAAABEf/aAAgBAgEBPwG1/8QAGhABAQADAQEAAAAAAAAAAAAAAREAITFBUf/aAAgBAQAGPwKYvA9G2Q1//8QAGhABAAIDAQAAAAAAAAAAAAAAAQARITFBcf/aAAgBAQABPyG0A6gk4x8M6hD8o//aAAwDAQACAAMAAAAQ8//EABYRAQEBAAAAAAAAAAAAAAAAAAARAf/aAAgBAwEBPxA0f//EABYRAQEBAAAAAAAAAAAAAAAAAAARAf/aAAgBAgEBPxBf/8QAGhABAAMBAQEAAAAAAAAAAAAAAQARITFBUf/aAAgBAQABPxAt8Pavq6rKIBqvQnYz7E0uT//Z");
    }

    private byte[] uniquePngBytes() {
        try {
            BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, (int) (System.nanoTime() & 0x00FFFFFF));
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            ImageIO.write(image, "png", buffer);
            return buffer.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to generate unique PNG test bytes", ex);
        }
    }

    private String sha256Hex(byte[] bytes) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private long ensurePolicyRule(String code, String severity) {
        Long existing = jdbcTemplate.query(
            "select id from policy_rule where rule_code = ?",
            rs -> rs.next() ? rs.getLong(1) : null,
            code
        );
        if (existing != null) {
            return existing;
        }
        jdbcTemplate.update(
            "insert into policy_rule (rule_code, description_text, severity, is_active) values (?, ?, ?, true)",
            code,
            "Auto test rule",
            severity
        );
        Long created = jdbcTemplate.queryForObject("select id from policy_rule where rule_code = ?", Long.class, code);
        if (created == null) {
            throw new IllegalStateException("Unable to create policy rule");
        }
        return created;
    }

    private void ensureSensitiveTerm(String normalizedTerm, String tag, long ruleId) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from sensitive_dictionary where normalized_term = ? and tag = ?",
            Integer.class,
            normalizedTerm,
            tag
        );
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.update(
            "insert into sensitive_dictionary (term, normalized_term, tag, rule_id, is_active) values (?, ?, ?, ?, true)",
            normalizedTerm,
            normalizedTerm,
            tag,
            ruleId
        );
    }

    private void ensureLocation(long locationId) {
        Integer count = jdbcTemplate.queryForObject("select count(*) from location where id = ?", Integer.class, locationId);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.update(
            "insert into location (id, code, name, timezone, is_active) values (?, ?, ?, 'UTC', true)",
            locationId,
            "LOC-" + locationId,
            "Location " + locationId
        );
    }
}
