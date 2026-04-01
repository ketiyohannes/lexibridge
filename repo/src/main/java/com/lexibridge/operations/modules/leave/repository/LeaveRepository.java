package com.lexibridge.operations.modules.leave.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class LeaveRepository {

    private final JdbcTemplate jdbcTemplate;

    public LeaveRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long createRequest(long locationId,
                              long requesterUserId,
                              String leaveType,
                              LocalDate startDate,
                              LocalDate endDate,
                              int durationMinutes,
                              Long formVersionId,
                              String formPayloadJson,
                              LocalDateTime slaDeadline) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                """
                insert into leave_request
                (location_id, requester_user_id, leave_type, start_date, end_date, duration_minutes, form_version_id, form_payload_json, status, current_step, sla_deadline_at)
                values (?, ?, ?, ?, ?, ?, ?, cast(? as json), 'PENDING_APPROVAL', 'ROUTING', ?)
                """,
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, locationId);
            ps.setLong(2, requesterUserId);
            ps.setString(3, leaveType);
            ps.setObject(4, startDate);
            ps.setObject(5, endDate);
            ps.setInt(6, durationMinutes);
            ps.setObject(7, formVersionId);
            ps.setString(8, formPayloadJson);
            ps.setObject(9, slaDeadline);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public boolean formVersionBelongsToLocation(long locationId, long formVersionId) {
        Integer count = jdbcTemplate.queryForObject(
            """
            select count(*)
            from leave_form_version fv
            join leave_form_definition fd on fd.id = fv.form_definition_id
            where fv.id = ? and fd.location_id = ? and fd.is_active = true
            """,
            Integer.class,
            formVersionId,
            locationId
        );
        return count != null && count > 0;
    }

    public long addAttachment(long leaveRequestId,
                              String storagePath,
                              String mimeType,
                              long fileSizeBytes,
                              String checksumSha256,
                              long createdBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                """
                insert into leave_request_attachment
                (leave_request_id, storage_path, mime_type, file_size_bytes, checksum_sha256, created_by)
                values (?, ?, ?, ?, ?, ?)
                """,
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, leaveRequestId);
            ps.setString(2, storagePath);
            ps.setString(3, mimeType);
            ps.setLong(4, fileSizeBytes);
            ps.setString(5, checksumSha256);
            ps.setLong(6, createdBy);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public List<Map<String, Object>> attachments(long leaveRequestId) {
        return jdbcTemplate.queryForList(
            """
            select id, storage_path, mime_type, file_size_bytes, checksum_sha256, created_by, created_at
            from leave_request_attachment
            where leave_request_id = ?
            order by id desc
            """,
            leaveRequestId
        );
    }

    public Map<String, Object> attachmentById(long attachmentId) {
        return jdbcTemplate.query(
            """
            select id, leave_request_id, storage_path, mime_type, file_size_bytes, checksum_sha256, created_by, created_at
            from leave_request_attachment
            where id = ?
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                return Map.of(
                    "id", rs.getLong("id"),
                    "leave_request_id", rs.getLong("leave_request_id"),
                    "storage_path", rs.getString("storage_path"),
                    "mime_type", rs.getString("mime_type"),
                    "file_size_bytes", rs.getLong("file_size_bytes"),
                    "checksum_sha256", rs.getString("checksum_sha256"),
                    "created_by", rs.getLong("created_by"),
                    "created_at", rs.getObject("created_at")
                );
            },
            attachmentId
        );
    }

    public Long leaveRequestLocation(long leaveRequestId) {
        return jdbcTemplate.query(
            "select location_id from leave_request where id = ?",
            rs -> rs.next() ? (Long) rs.getObject(1) : null,
            leaveRequestId
        );
    }

    public Optional<Long> requesterOrgUnit(long requesterUserId) {
        return jdbcTemplate.query(
            "select org_unit_id from app_user where id = ?",
            rs -> rs.next() ? Optional.ofNullable((Long) rs.getObject(1)) : Optional.empty(),
            requesterUserId
        );
    }

    public List<Map<String, Object>> matchingRules(long locationId,
                                                    Long orgUnitId,
                                                    String leaveType,
                                                    int durationMinutes) {
        return jdbcTemplate.queryForList(
            """
            select id, location_id, priority, leave_type, org_unit_id, min_duration_minutes, max_duration_minutes,
                   approver_role_code, approver_user_id
            from approval_rule
            where location_id = ?
              and is_active = true
              and (? is null or org_unit_id is null or org_unit_id = ?)
              and (leave_type is null or leave_type = ?)
              and (min_duration_minutes is null or min_duration_minutes <= ?)
              and (max_duration_minutes is null or max_duration_minutes >= ?)
            """,
            locationId,
            orgUnitId,
            orgUnitId,
            leaveType,
            durationMinutes,
            durationMinutes
        );
    }

    public Optional<Long> findApproverByRole(long locationId, String roleCode) {
        return jdbcTemplate.query(
            """
            select u.id
            from app_user u
            join app_user_role ur on ur.user_id = u.id
            join app_role r on r.id = ur.role_id
            where u.location_id = ? and u.is_active = true and r.code = ?
            order by u.id asc
            limit 1
            """,
            rs -> rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty(),
            locationId,
            roleCode
        );
    }

    public void createApprovalTask(long leaveRequestId, long approverUserId, LocalDateTime dueAt) {
        jdbcTemplate.update(
            """
            insert into approval_task (leave_request_id, approver_user_id, status, due_at)
            values (?, ?, 'PENDING', ?)
            """,
            leaveRequestId,
            approverUserId,
            dueAt
        );
    }

    public void updateRequestStep(long leaveRequestId, String step) {
        jdbcTemplate.update(
            "update leave_request set current_step = ? where id = ?",
            step,
            leaveRequestId
        );
    }

    public int withdraw(long leaveRequestId) {
        return jdbcTemplate.update(
            """
            update leave_request
            set status = 'WITHDRAWN', withdrawn_at = current_timestamp
            where id = ? and status in ('PENDING_APPROVAL', 'NEEDS_CORRECTION')
            """,
            leaveRequestId
        );
    }

    public Map<String, Object> requestById(long leaveRequestId) {
        return jdbcTemplate.query(
            """
            select id, location_id, requester_user_id, leave_type, start_date, end_date, duration_minutes, status
            from leave_request
            where id = ?
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                return Map.of(
                    "id", rs.getLong("id"),
                    "location_id", rs.getLong("location_id"),
                    "requester_user_id", rs.getLong("requester_user_id"),
                    "leave_type", rs.getString("leave_type"),
                    "start_date", rs.getObject("start_date", LocalDate.class),
                    "end_date", rs.getObject("end_date", LocalDate.class),
                    "duration_minutes", rs.getInt("duration_minutes"),
                    "status", rs.getString("status")
                );
            },
            leaveRequestId
        );
    }

    public int resubmitCorrection(long leaveRequestId,
                                  String leaveType,
                                  LocalDate startDate,
                                  LocalDate endDate,
                                  int durationMinutes,
                                  Long formVersionId,
                                  String formPayloadJson,
                                  LocalDateTime slaDeadline) {
        return jdbcTemplate.update(
            """
            update leave_request
            set leave_type = ?,
                start_date = ?,
                end_date = ?,
                duration_minutes = ?,
                form_version_id = ?,
                form_payload_json = cast(? as json),
                status = 'PENDING_APPROVAL',
                current_step = 'ROUTING',
                sla_paused = false,
                withdrawn_at = null,
                sla_deadline_at = ?
            where id = ? and status = 'NEEDS_CORRECTION'
            """,
            leaveType,
            startDate,
            endDate,
            durationMinutes,
            formVersionId,
            formPayloadJson,
            slaDeadline,
            leaveRequestId
        );
    }

    public List<Long> overdueApprovals() {
        return jdbcTemplate.query(
            "select id from approval_task where status = 'PENDING' and due_at <= current_timestamp",
            (rs, rowNum) -> rs.getLong(1)
        );
    }

    public void markTaskOverdue(long taskId) {
        jdbcTemplate.update("update approval_task set status = 'OVERDUE' where id = ?", taskId);
    }

    public Map<String, Object> summary(Long locationId) {
        String reqWhere = locationId == null ? "" : " and lr.location_id = ?";
        String taskWhere = locationId == null ? "" : " and exists (select 1 from leave_request lr where lr.id = approval_task.leave_request_id and lr.location_id = ?)";
        Integer pending = jdbcTemplate.queryForObject(
            "select count(*) from approval_task where status = 'PENDING'" + taskWhere,
            Integer.class,
            locationId == null ? new Object[]{} : new Object[]{locationId}
        );
        Integer overdue = jdbcTemplate.queryForObject(
            "select count(*) from approval_task where status = 'OVERDUE'" + taskWhere,
            Integer.class,
            locationId == null ? new Object[]{} : new Object[]{locationId}
        );
        Integer corrections = jdbcTemplate.queryForObject(
            "select count(*) from leave_request lr where status = 'NEEDS_CORRECTION'" + reqWhere,
            Integer.class,
            locationId == null ? new Object[]{} : new Object[]{locationId}
        );
        Integer breachesToday = jdbcTemplate.queryForObject(
            "select count(*) from approval_task where status = 'OVERDUE' and date(created_at) = current_date" + taskWhere,
            Integer.class,
            locationId == null ? new Object[]{} : new Object[]{locationId}
        );
        return Map.of(
            "pendingApprovals", pending == null ? 0 : pending,
            "overdueApprovals", overdue == null ? 0 : overdue,
            "requestsInCorrection", corrections == null ? 0 : corrections,
            "slaBreachesToday", breachesToday == null ? 0 : breachesToday
        );
    }

    public List<Map<String, Object>> recentApprovalQueue(Long locationId, int limit) {
        if (locationId == null) {
            return jdbcTemplate.queryForList(
                """
                select at.id as task_id,
                       at.status as task_status,
                       at.due_at,
                       at.approver_user_id,
                       lr.id as leave_request_id,
                       lr.leave_type,
                       lr.start_date,
                       lr.end_date,
                       lr.duration_minutes,
                       lr.status as request_status,
                       lr.current_step,
                       lr.sla_deadline_at,
                       lr.sla_paused,
                       requester.full_name as requester_name,
                       approver.full_name as approver_name
                from approval_task at
                join leave_request lr on lr.id = at.leave_request_id
                join app_user requester on requester.id = lr.requester_user_id
                join app_user approver on approver.id = at.approver_user_id
                order by at.created_at desc
                limit ?
                """,
                limit
            );
        }
        return jdbcTemplate.queryForList(
            """
            select at.id as task_id,
                   at.status as task_status,
                   at.due_at,
                   at.approver_user_id,
                   lr.id as leave_request_id,
                   lr.leave_type,
                   lr.start_date,
                   lr.end_date,
                   lr.duration_minutes,
                   lr.status as request_status,
                   lr.current_step,
                   lr.sla_deadline_at,
                   lr.sla_paused,
                   requester.full_name as requester_name,
                   approver.full_name as approver_name
            from approval_task at
            join leave_request lr on lr.id = at.leave_request_id
            join app_user requester on requester.id = lr.requester_user_id
            join app_user approver on approver.id = at.approver_user_id
            where lr.location_id = ?
            order by at.created_at desc
            limit ?
            """,
            locationId,
            limit
        );
    }

    public List<Map<String, Object>> recentLeaveRequestsByRequester(long requesterUserId, int limit) {
        return jdbcTemplate.queryForList(
            """
            select id,
                   leave_type,
                   start_date,
                   end_date,
                   duration_minutes,
                   status,
                   current_step,
                   sla_paused,
                   sla_deadline_at,
                   created_at
            from leave_request
            where requester_user_id = ?
            order by created_at desc
            limit ?
            """,
            requesterUserId,
            limit
        );
    }

    public int approveTask(long taskId, String decisionNote) {
        List<Long> requestIds = jdbcTemplate.query(
            "select leave_request_id from approval_task where id = ?",
            (rs, rowNum) -> rs.getLong(1),
            taskId
        );
        if (requestIds.isEmpty()) {
            return 0;
        }

        int updated = jdbcTemplate.update(
            """
            update approval_task
            set status = 'APPROVED',
                decided_at = current_timestamp,
                decision_note = ?
            where id = ? and status in ('PENDING', 'OVERDUE')
            """,
            decisionNote,
            taskId
        );
        if (updated > 0) {
            jdbcTemplate.update(
                """
                update leave_request
                set status = 'APPROVED',
                    current_step = 'COMPLETED'
                where id = ?
                """,
                requestIds.getFirst()
            );
        }
        return updated;
    }

    public int returnTaskForCorrection(long taskId, String decisionNote) {
        List<Long> requestIds = jdbcTemplate.query(
            "select leave_request_id from approval_task where id = ?",
            (rs, rowNum) -> rs.getLong(1),
            taskId
        );
        if (requestIds.isEmpty()) {
            return 0;
        }

        int updated = jdbcTemplate.update(
            """
            update approval_task
            set status = 'RETURNED_FOR_CORRECTION',
                decided_at = current_timestamp,
                decision_note = ?
            where id = ? and status in ('PENDING', 'OVERDUE')
            """,
            decisionNote,
            taskId
        );
        if (updated > 0) {
            jdbcTemplate.update(
                """
                update leave_request
                set status = 'NEEDS_CORRECTION',
                    current_step = 'REQUESTER_CORRECTION',
                    sla_paused = true
                where id = ?
                """,
                requestIds.getFirst()
            );
        }
        return updated;
    }

    public List<Map<String, Object>> activeFormVersions(long locationId) {
        return jdbcTemplate.queryForList(
            """
            select fv.id, fd.name, fv.version_no, fv.schema_json, fv.created_at
            from leave_form_version fv
            join leave_form_definition fd on fd.id = fv.form_definition_id
            where fd.location_id = ? and fd.is_active = true
            order by fd.id desc, fv.version_no desc
            """,
            locationId
        );
    }

    public List<Map<String, Object>> formDefinitions(long locationId) {
        return jdbcTemplate.queryForList(
            """
            select fd.id,
                   fd.location_id,
                   fd.name,
                   fd.is_active,
                   fd.created_at,
                   coalesce(max(fv.version_no), 0) as latest_version_no
            from leave_form_definition fd
            left join leave_form_version fv on fv.form_definition_id = fd.id
            where fd.location_id = ?
            group by fd.id, fd.location_id, fd.name, fd.is_active, fd.created_at
            order by fd.id desc
            """,
            locationId
        );
    }

    public long createFormDefinition(long locationId, String name, long createdBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                """
                insert into leave_form_definition (location_id, name, is_active, created_by)
                values (?, ?, true, ?)
                """,
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, locationId);
            ps.setString(2, name);
            ps.setLong(3, createdBy);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Long formDefinitionLocation(long definitionId) {
        return jdbcTemplate.query(
            "select location_id from leave_form_definition where id = ? and is_active = true",
            rs -> rs.next() ? (Long) rs.getObject(1) : null,
            definitionId
        );
    }

    public int nextFormVersionNo(long definitionId) {
        Integer next = jdbcTemplate.queryForObject(
            "select coalesce(max(version_no), 0) + 1 from leave_form_version where form_definition_id = ?",
            Integer.class,
            definitionId
        );
        return next == null ? 1 : next;
    }

    public long createFormVersion(long definitionId, int versionNo, String schemaJson, long createdBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                """
                insert into leave_form_version (form_definition_id, version_no, schema_json, created_by)
                values (?, ?, cast(? as json), ?)
                """,
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, definitionId);
            ps.setInt(2, versionNo);
            ps.setString(3, schemaJson);
            ps.setLong(4, createdBy);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }
}
