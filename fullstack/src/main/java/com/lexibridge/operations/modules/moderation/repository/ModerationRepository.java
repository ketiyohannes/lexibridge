package com.lexibridge.operations.modules.moderation.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ModerationRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ModerationRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public long createCase(long locationId, String targetType, long targetId, List<Map<String, Object>> hits) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                """
                insert into moderation_case
                (location_id, target_type, target_id, status, sensitive_hits_json)
                values (?, ?, ?, 'PENDING', cast(? as json))
                """,
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, locationId);
            ps.setString(2, targetType);
            ps.setLong(3, targetId);
            ps.setString(4, toJson(hits));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void resolveCase(long caseId, String status, long reviewerUserId, String reviewerNote) {
        jdbcTemplate.update(
            """
            update moderation_case
            set status = ?, reviewer_user_id = ?, reviewer_note = ?, resolved_at = current_timestamp
            where id = ?
            """,
            status,
            reviewerUserId,
            reviewerNote,
            caseId
        );
    }

    public List<String> activeSensitiveTerms() {
        return jdbcTemplate.query(
            "select normalized_term from sensitive_dictionary where is_active = true",
            (rs, rowNum) -> rs.getString(1)
        );
    }

    public List<Map<String, Object>> activeSensitiveEntries() {
        return jdbcTemplate.queryForList(
            """
            select sd.id,
                   sd.normalized_term,
                   sd.tag,
                   pr.id as rule_id,
                   pr.rule_code,
                   pr.severity
            from sensitive_dictionary sd
            join policy_rule pr on pr.id = sd.rule_id
            where sd.is_active = true and pr.is_active = true
            order by sd.id asc
            """
        );
    }

    public void recordConfirmedViolation(long offenderUserId, long reviewerUserId, String reason, String appealNote) {
        jdbcTemplate.update(
            """
            insert into user_penalty (user_id, penalty_type, start_at, reason_text, appeal_note, created_by)
            values (?, 'WARNING', current_timestamp, ?, ?, ?)
            """,
            offenderUserId,
            reason,
            appealNote,
            reviewerUserId
        );
    }

    public int confirmedViolationsInLastDays(long offenderUserId, int days) {
        Integer count = jdbcTemplate.queryForObject(
            """
            select count(*)
            from user_penalty
            where user_id = ?
              and penalty_type = 'WARNING'
              and created_at >= ?
            """,
            Integer.class,
            offenderUserId,
            LocalDateTime.now().minusDays(days)
        );
        return count == null ? 0 : count;
    }

    public void createSuspension(long offenderUserId, long reviewerUserId, String reason, String appealNote, int days) {
        LocalDateTime start = LocalDateTime.now();
        LocalDateTime end = start.plusDays(days);
        jdbcTemplate.update(
            """
            insert into user_penalty (user_id, penalty_type, start_at, end_at, reason_text, appeal_note, created_by)
            values (?, 'SUSPENSION_30_DAYS', ?, ?, ?, ?, ?)
            """,
            offenderUserId,
            start,
            end,
            reason,
            appealNote,
            reviewerUserId
        );
    }

    public boolean hasActiveSuspension(long userId) {
        Integer count = jdbcTemplate.queryForObject(
            """
            select count(*)
            from user_penalty
            where user_id = ?
              and penalty_type = 'SUSPENSION_30_DAYS'
              and end_at > current_timestamp
            """,
            Integer.class,
            userId
        );
        return count != null && count > 0;
    }

    public List<Long> usersRequiringAutoSuspension() {
        return jdbcTemplate.query(
            """
            select w.user_id
            from (
                select user_id
                from user_penalty
                where penalty_type = 'WARNING'
                  and created_at >= ?
                group by user_id
                having count(*) >= 3
            ) w
            where not exists (
                select 1
                from user_penalty ups
                where ups.user_id = w.user_id
                  and ups.penalty_type = 'SUSPENSION_30_DAYS'
                  and ups.end_at > current_timestamp
            )
            """,
            (rs, rowNum) -> rs.getLong(1),
            LocalDateTime.now().minusDays(90)
        );
    }

    public Map<String, Object> summary(Long locationId) {
        String where = locationId == null ? "" : " and location_id = ?";
        Integer pending = jdbcTemplate.queryForObject(
            "select count(*) from moderation_case where status = 'PENDING'" + where,
            Integer.class,
            locationId == null ? new Object[]{} : new Object[]{locationId}
        );
        Integer approvedToday = jdbcTemplate.queryForObject(
            "select count(*) from moderation_case where status = 'APPROVED' and date(resolved_at) = current_date" + where,
            Integer.class,
            locationId == null ? new Object[]{} : new Object[]{locationId}
        );
        Integer rejectedToday = jdbcTemplate.queryForObject(
            "select count(*) from moderation_case where status = 'REJECTED' and date(resolved_at) = current_date" + where,
            Integer.class,
            locationId == null ? new Object[]{} : new Object[]{locationId}
        );
        Integer activeSuspensions = jdbcTemplate.queryForObject(
            """
            select count(*)
            from user_penalty up
            join app_user au on au.id = up.user_id
            where up.penalty_type = 'SUSPENSION_30_DAYS'
              and up.end_at > current_timestamp
            """ + (locationId == null ? "" : " and au.location_id = ?"),
            Integer.class,
            locationId == null ? new Object[]{} : new Object[]{locationId}
        );
        return Map.of(
            "pendingCount", pending == null ? 0 : pending,
            "approvedToday", approvedToday == null ? 0 : approvedToday,
            "rejectedToday", rejectedToday == null ? 0 : rejectedToday,
            "activeSuspensions", activeSuspensions == null ? 0 : activeSuspensions
        );
    }

    public List<Map<String, Object>> recentCases(Long locationId, int limit) {
        if (locationId == null) {
            return jdbcTemplate.queryForList(
                """
                select mc.id,
                       mc.location_id,
                       mc.target_type,
                       mc.target_id,
                       mc.status,
                       mc.sensitive_hits_json,
                       mc.created_at,
                       mc.resolved_at,
                       mc.reviewer_note,
                       case
                           when mc.target_type = 'POST' then cp.title
                           when mc.target_type = 'QNA' then cq.question_text
                           else null
                       end as target_title,
                       case
                           when mc.target_type = 'POST' then cp.body_html
                           when mc.target_type = 'COMMENT' then cc.body_text
                           when mc.target_type = 'QNA' then concat(cq.question_text, ifnull(concat('\n\nAnswer: ', cq.answer_text), ''))
                           else null
                       end as target_body,
                       case
                           when mc.target_type = 'POST' then cp.author_user_id
                           when mc.target_type = 'COMMENT' then cc.author_user_id
                           when mc.target_type = 'QNA' then cq.author_user_id
                           else null
                       end as target_author_user_id,
                       case
                           when mc.target_type = 'POST' then cp.status
                           when mc.target_type = 'COMMENT' then cc.status
                           when mc.target_type = 'QNA' then cq.status
                           else null
                       end as target_status,
                       (select count(*) from moderation_case_media mcm where mcm.case_id = mc.id) as case_media_count
                from moderation_case mc
                left join community_post cp on mc.target_type = 'POST' and cp.id = mc.target_id
                left join community_comment cc on mc.target_type = 'COMMENT' and cc.id = mc.target_id
                left join community_qna cq on mc.target_type = 'QNA' and cq.id = mc.target_id
                order by mc.created_at desc
                limit ?
                """,
                limit
            );
        }
        return jdbcTemplate.queryForList(
            """
            select mc.id,
                   mc.location_id,
                   mc.target_type,
                   mc.target_id,
                   mc.status,
                   mc.sensitive_hits_json,
                   mc.created_at,
                   mc.resolved_at,
                   mc.reviewer_note,
                   case
                       when mc.target_type = 'POST' then cp.title
                       when mc.target_type = 'QNA' then cq.question_text
                       else null
                   end as target_title,
                   case
                       when mc.target_type = 'POST' then cp.body_html
                       when mc.target_type = 'COMMENT' then cc.body_text
                       when mc.target_type = 'QNA' then concat(cq.question_text, ifnull(concat('\n\nAnswer: ', cq.answer_text), ''))
                       else null
                   end as target_body,
                   case
                       when mc.target_type = 'POST' then cp.author_user_id
                       when mc.target_type = 'COMMENT' then cc.author_user_id
                       when mc.target_type = 'QNA' then cq.author_user_id
                       else null
                   end as target_author_user_id,
                   case
                       when mc.target_type = 'POST' then cp.status
                       when mc.target_type = 'COMMENT' then cc.status
                       when mc.target_type = 'QNA' then cq.status
                       else null
                   end as target_status,
                   (select count(*) from moderation_case_media mcm where mcm.case_id = mc.id) as case_media_count
            from moderation_case mc
            left join community_post cp on mc.target_type = 'POST' and cp.id = mc.target_id
            left join community_comment cc on mc.target_type = 'COMMENT' and cc.id = mc.target_id
            left join community_qna cq on mc.target_type = 'QNA' and cq.id = mc.target_id
            where mc.location_id = ?
            order by mc.created_at desc
            limit ?
            """,
            locationId,
            limit
        );
    }

    public Optional<Map<String, Object>> caseById(long caseId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            """
            select mc.id,
                   mc.location_id,
                   mc.target_type,
                   mc.target_id,
                   mc.status,
                   mc.sensitive_hits_json,
                   mc.created_at,
                   mc.resolved_at,
                   mc.reviewer_note,
                   case
                       when mc.target_type = 'POST' then cp.title
                       when mc.target_type = 'QNA' then cq.question_text
                       else null
                   end as target_title,
                   case
                       when mc.target_type = 'POST' then cp.body_html
                       when mc.target_type = 'COMMENT' then cc.body_text
                       when mc.target_type = 'QNA' then concat(cq.question_text, ifnull(concat('\n\nAnswer: ', cq.answer_text), ''))
                       else null
                   end as target_body,
                   case
                       when mc.target_type = 'POST' then cp.author_user_id
                       when mc.target_type = 'COMMENT' then cc.author_user_id
                       when mc.target_type = 'QNA' then cq.author_user_id
                       else null
                   end as target_author_user_id,
                   case
                       when mc.target_type = 'POST' then cp.status
                       when mc.target_type = 'COMMENT' then cc.status
                       when mc.target_type = 'QNA' then cq.status
                       else null
                   end as target_status,
                   (select count(*) from moderation_case_media mcm where mcm.case_id = mc.id) as case_media_count
            from moderation_case mc
            left join community_post cp on mc.target_type = 'POST' and cp.id = mc.target_id
            left join community_comment cc on mc.target_type = 'COMMENT' and cc.id = mc.target_id
            left join community_qna cq on mc.target_type = 'QNA' and cq.id = mc.target_id
            where mc.id = ?
            """,
            caseId
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(rows.getFirst());
    }

    public long createUserReport(long locationId, long reporterUserId, String targetType, long targetId, String reasonText) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                """
                insert into user_report
                (location_id, reporter_user_id, target_type, target_id, reason_text, disposition)
                values (?, ?, ?, ?, ?, 'OPEN')
                """,
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, locationId);
            ps.setLong(2, reporterUserId);
            ps.setString(3, targetType);
            ps.setLong(4, targetId);
            ps.setString(5, reasonText);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public int resolveUserReport(long reportId,
                                 String disposition,
                                 long moderatorUserId,
                                 String resolutionNote,
                                 String penaltySummary) {
        return jdbcTemplate.update(
            """
            update user_report
            set disposition = ?, moderator_user_id = ?, resolution_note = ?, penalty_summary = ?, resolved_at = current_timestamp
            where id = ?
            """,
            disposition,
            moderatorUserId,
            resolutionNote,
            penaltySummary,
            reportId
        );
    }

    public List<Map<String, Object>> reportsByReporter(long reporterUserId) {
        return jdbcTemplate.queryForList(
            """
            select id, location_id, target_type, target_id, reason_text, disposition, moderator_user_id, resolution_note, penalty_summary, created_at, resolved_at
            from user_report
            where reporter_user_id = ?
            order by id desc
            """,
            reporterUserId
        );
    }

    public Long locationForCase(long caseId) {
        return jdbcTemplate.query(
            "select location_id from moderation_case where id = ?",
            rs -> rs.next() ? (Long) rs.getObject(1) : null,
            caseId
        );
    }

    public Long locationForReport(long reportId) {
        return jdbcTemplate.query(
            "select location_id from user_report where id = ?",
            rs -> rs.next() ? (Long) rs.getObject(1) : null,
            reportId
        );
    }

    public Long locationForTarget(String targetType, long targetId) {
        if ("POST".equalsIgnoreCase(targetType)) {
            return jdbcTemplate.query(
                "select location_id from community_post where id = ?",
                rs -> rs.next() ? (Long) rs.getObject(1) : null,
                targetId
            );
        }
        if ("COMMENT".equalsIgnoreCase(targetType)) {
            return jdbcTemplate.query(
                "select location_id from community_comment where id = ?",
                rs -> rs.next() ? (Long) rs.getObject(1) : null,
                targetId
            );
        }
        if ("QNA".equalsIgnoreCase(targetType)) {
            return jdbcTemplate.query(
                "select location_id from community_qna where id = ?",
                rs -> rs.next() ? (Long) rs.getObject(1) : null,
                targetId
            );
        }
        return null;
    }

    public long createPost(long locationId, long authorUserId, String title, String bodyHtml) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                """
                insert into community_post
                (location_id, author_user_id, content_type, title, body_html, status)
                values (?, ?, 'POST', ?, ?, 'PUBLISHED')
                """,
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, locationId);
            ps.setLong(2, authorUserId);
            ps.setString(3, title);
            ps.setString(4, bodyHtml);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public long createComment(long locationId, long postId, long authorUserId, String bodyText) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                """
                insert into community_comment
                (location_id, post_id, author_user_id, body_text, status)
                values (?, ?, ?, ?, 'PUBLISHED')
                """,
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, locationId);
            ps.setLong(2, postId);
            ps.setLong(3, authorUserId);
            ps.setString(4, bodyText);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public long createQna(long locationId, long authorUserId, String questionText, String answerText) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                """
                insert into community_qna
                (location_id, author_user_id, question_text, answer_text, status)
                values (?, ?, ?, ?, 'PUBLISHED')
                """,
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, locationId);
            ps.setLong(2, authorUserId);
            ps.setString(3, questionText);
            ps.setString(4, answerText);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public long insertCaseMedia(long caseId,
                                String storagePath,
                                String mimeType,
                                long fileSizeBytes,
                                String checksumSha256,
                                long createdBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                """
                insert into moderation_case_media
                (case_id, storage_path, mime_type, file_size_bytes, checksum_sha256, created_by)
                values (?, ?, ?, ?, ?, ?)
                """,
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, caseId);
            ps.setString(2, storagePath);
            ps.setString(3, mimeType);
            ps.setLong(4, fileSizeBytes);
            ps.setString(5, checksumSha256);
            ps.setLong(6, createdBy);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public List<Map<String, Object>> caseMedia(long caseId) {
        return jdbcTemplate.queryForList(
            """
            select id, case_id, storage_path, mime_type, file_size_bytes, checksum_sha256, created_by, created_at
            from moderation_case_media
            where case_id = ?
            order by id desc
            """,
            caseId
        );
    }

    public Map<String, Object> caseMediaById(long mediaId) {
        return jdbcTemplate.query(
            """
            select id, case_id, storage_path, mime_type, file_size_bytes, checksum_sha256, created_by, created_at
            from moderation_case_media
            where id = ?
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                return Map.of(
                    "id", rs.getLong("id"),
                    "case_id", rs.getLong("case_id"),
                    "storage_path", rs.getString("storage_path"),
                    "mime_type", rs.getString("mime_type"),
                    "file_size_bytes", rs.getLong("file_size_bytes"),
                    "checksum_sha256", rs.getString("checksum_sha256"),
                    "created_by", rs.getLong("created_by"),
                    "created_at", rs.getObject("created_at")
                );
            },
            mediaId
        );
    }

    public List<Map<String, Object>> penaltiesForUser(long userId) {
        return jdbcTemplate.queryForList(
            """
            select id, penalty_type, start_at, end_at, reason_text, appeal_note, created_at
            from user_penalty
            where user_id = ?
            order by created_at desc
            """,
            userId
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to serialize moderation hits.", e);
        }
    }
}
