package com.lexibridge.operations.security.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ScopeLookupService {

    private final JdbcTemplate jdbcTemplate;

    public ScopeLookupService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long bookingLocation(long bookingOrderId) {
        return jdbcTemplate.query(
            "select location_id from booking_order where id = ?",
            rs -> rs.next() ? (Long) rs.getObject(1) : null,
            bookingOrderId
        );
    }

    public Long activeUserLocationById(long userId) {
        return jdbcTemplate.query(
            "select location_id from app_user where id = ? and is_active = true",
            rs -> rs.next() ? (Long) rs.getObject(1) : null,
            userId
        );
    }

    public Long refundLocation(long refundId) {
        return jdbcTemplate.query(
            """
            select bo.location_id
            from refund_request rr
            join tender_entry te on te.id = rr.tender_entry_id
            join booking_order bo on bo.id = te.booking_order_id
            where rr.id = ?
            """,
            rs -> rs.next() ? (Long) rs.getObject(1) : null,
            refundId
        );
    }

    public Long reconciliationRunLocation(long runId) {
        return jdbcTemplate.query(
            "select location_id from reconciliation_run where id = ?",
            rs -> rs.next() ? (Long) rs.getObject(1) : null,
            runId
        );
    }

    public Long contentItemLocation(long contentItemId) {
        return jdbcTemplate.query(
            "select location_id from content_item where id = ?",
            rs -> rs.next() ? (Long) rs.getObject(1) : null,
            contentItemId
        );
    }

    public Long leaveRequester(long leaveRequestId) {
        return jdbcTemplate.query(
            "select requester_user_id from leave_request where id = ?",
            rs -> rs.next() ? (Long) rs.getObject(1) : null,
            leaveRequestId
        );
    }

    public int activeApproverTaskCount(long leaveRequestId, long approverUserId) {
        Integer count = jdbcTemplate.queryForObject(
            """
            select count(*)
            from approval_task
            where leave_request_id = ?
              and approver_user_id = ?
              and status in ('PENDING', 'OVERDUE')
            """,
            Integer.class,
            leaveRequestId,
            approverUserId
        );
        return count == null ? 0 : count;
    }

    public Long approvalTaskApprover(long approvalTaskId) {
        return jdbcTemplate.query(
            "select approver_user_id from approval_task where id = ?",
            rs -> rs.next() ? (Long) rs.getObject(1) : null,
            approvalTaskId
        );
    }

    public Long tenderLocation(long tenderEntryId) {
        return jdbcTemplate.query(
            """
            select bo.location_id
            from tender_entry te
            join booking_order bo on bo.id = te.booking_order_id
            where te.id = ?
            """,
            rs -> rs.next() ? (Long) rs.getObject(1) : null,
            tenderEntryId
        );
    }

    public Long reconciliationExceptionLocation(long exceptionId) {
        return jdbcTemplate.query(
            """
            select rr.location_id
            from reconciliation_exception re
            join reconciliation_run rr on rr.id = re.run_id
            where re.id = ?
            """,
            rs -> rs.next() ? (Long) rs.getObject(1) : null,
            exceptionId
        );
    }

    public Long moderationCaseLocation(long caseId) {
        return jdbcTemplate.query(
            "select location_id from moderation_case where id = ?",
            rs -> rs.next() ? (Long) rs.getObject(1) : null,
            caseId
        );
    }

    public Long userReportLocation(long reportId) {
        return jdbcTemplate.query(
            "select location_id from user_report where id = ?",
            rs -> rs.next() ? (Long) rs.getObject(1) : null,
            reportId
        );
    }
}
