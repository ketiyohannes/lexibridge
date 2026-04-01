package com.lexibridge.operations.security.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AuthorizationScopeService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationScopeService.class);

    private final JdbcTemplate jdbcTemplate;

    public AuthorizationScopeService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void assertLocationAccess(Long locationId) {
        if (locationId == null) {
            log.warn("Location scope denied: missing locationId");
            throw new AccessDeniedException("Location is required.");
        }

        if (isAdmin()) {
            return;
        }

        Authentication auth = requireAuthentication();
        if (hasRole(auth, "ROLE_DEVICE_SERVICE")) {
            Long deviceLocation = jdbcTemplate.query(
                "select location_id from device_client where client_key = ? and status = 'ACTIVE'",
                rs -> rs.next() ? rs.getLong(1) : null,
                auth.getName()
            );
            if (deviceLocation == null || !deviceLocation.equals(locationId)) {
                log.warn("Location scope denied for device client '{}' on location {}", auth.getName(), locationId);
                throw new AccessDeniedException("Requested location is outside device scope.");
            }
            return;
        }

        Long userLocation = jdbcTemplate.query(
            "select location_id from app_user where lower(username) = lower(?) and is_active = true",
            rs -> rs.next() ? rs.getLong(1) : null,
            auth.getName()
        );
        if (userLocation == null || !userLocation.equals(locationId)) {
            log.warn("Location scope denied for user '{}' on location {}", auth.getName(), locationId);
            throw new AccessDeniedException("Requested location is outside user scope.");
        }
    }

    public void assertActorUser(Long actorUserId) {
        if (actorUserId == null) {
            log.warn("Actor scope denied: missing actor user id");
            throw new AccessDeniedException("Actor user is required.");
        }

        if (isAdmin()) {
            return;
        }

        Authentication auth = requireAuthentication();
        if (hasRole(auth, "ROLE_DEVICE_SERVICE")) {
            Long actorLocation = jdbcTemplate.query(
                "select location_id from app_user where id = ? and is_active = true",
                rs -> rs.next() ? (Long) rs.getObject(1) : null,
                actorUserId
            );
            Long deviceLocation = jdbcTemplate.query(
                "select location_id from device_client where client_key = ? and status = 'ACTIVE'",
                rs -> rs.next() ? (Long) rs.getObject(1) : null,
                auth.getName()
            );
            if (actorLocation == null || deviceLocation == null || !deviceLocation.equals(actorLocation)) {
                log.warn("Actor scope denied for device '{}' to actorUserId {}", auth.getName(), actorUserId);
                throw new AccessDeniedException("Actor user is outside device location scope.");
            }
            return;
        }

        Long currentUserId = jdbcTemplate.query(
            "select id from app_user where lower(username) = lower(?) and is_active = true",
            rs -> rs.next() ? rs.getLong(1) : null,
            auth.getName()
        );
        if (currentUserId == null || !currentUserId.equals(actorUserId)) {
            log.warn("Actor scope denied: principal '{}' does not match actorUserId {}", auth.getName(), actorUserId);
            throw new AccessDeniedException("Actor user does not match authenticated principal.");
        }
    }

    public long requireCurrentUserId() {
        Authentication auth = requireAuthentication();
        if (hasRole(auth, "ROLE_DEVICE_SERVICE")) {
            throw new AccessDeniedException("Device clients do not map to a human user ID.");
        }
        Long currentUserId = jdbcTemplate.query(
            "select id from app_user where lower(username) = lower(?) and is_active = true",
            rs -> rs.next() ? rs.getLong(1) : null,
            auth.getName()
        );
        if (currentUserId == null) {
            throw new AccessDeniedException("Authenticated user not found.");
        }
        return currentUserId;
    }

    public void assertBookingAccess(long bookingOrderId) {
        Long locationId = jdbcTemplate.query(
            "select location_id from booking_order where id = ?",
            rs -> rs.next() ? (Long) rs.getObject(1) : null,
            bookingOrderId
        );
        if (locationId == null) {
            log.warn("Booking scope denied: bookingOrderId {} not found", bookingOrderId);
            throw new AccessDeniedException("Booking order not found.");
        }
        assertLocationAccess(locationId);
    }

    public void assertRefundScope(long refundId) {
        Long locationId = jdbcTemplate.query(
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
        if (locationId == null) {
            log.warn("Refund scope denied: refundId {} not found", refundId);
            throw new AccessDeniedException("Refund request not found.");
        }
        assertLocationAccess(locationId);
    }

    public void assertReconciliationRunScope(long runId) {
        Long locationId = jdbcTemplate.query(
            "select location_id from reconciliation_run where id = ?",
            rs -> rs.next() ? (Long) rs.getObject(1) : null,
            runId
        );
        if (locationId == null) {
            log.warn("Reconciliation run scope denied: runId {} not found", runId);
            throw new AccessDeniedException("Reconciliation run not found.");
        }
        assertLocationAccess(locationId);
    }

    public void assertContentItemScope(long contentItemId) {
        Long locationId = jdbcTemplate.query(
            "select location_id from content_item where id = ?",
            rs -> rs.next() ? (Long) rs.getObject(1) : null,
            contentItemId
        );
        if (locationId == null) {
            log.warn("Content item scope denied: contentItemId {} not found", contentItemId);
            throw new AccessDeniedException("Content item not found.");
        }
        assertLocationAccess(locationId);
    }

    public void assertLeaveRequestRequester(long leaveRequestId) {
        if (isAdmin()) {
            return;
        }
        Long requesterId = jdbcTemplate.query(
            "select requester_user_id from leave_request where id = ?",
            rs -> rs.next() ? (Long) rs.getObject(1) : null,
            leaveRequestId
        );
        if (requesterId == null) {
            log.warn("Leave requester scope denied: leaveRequestId {} not found", leaveRequestId);
            throw new AccessDeniedException("Leave request not found.");
        }
        assertActorUser(requesterId);
    }

    public void assertLeaveRequestReadAccess(long leaveRequestId) {
        if (isAdmin()) {
            return;
        }
        long currentUserId = requireCurrentUserId();
        Long requesterId = jdbcTemplate.query(
            "select requester_user_id from leave_request where id = ?",
            rs -> rs.next() ? (Long) rs.getObject(1) : null,
            leaveRequestId
        );
        if (requesterId == null) {
            log.warn("Leave read scope denied: leaveRequestId {} not found", leaveRequestId);
            throw new AccessDeniedException("Leave request not found.");
        }
        if (requesterId.equals(currentUserId)) {
            return;
        }
        Integer activeApproverTasks = jdbcTemplate.queryForObject(
            """
            select count(*)
            from approval_task
            where leave_request_id = ?
              and approver_user_id = ?
              and status in ('PENDING', 'OVERDUE')
            """,
            Integer.class,
            leaveRequestId,
            currentUserId
        );
        if (activeApproverTasks != null && activeApproverTasks > 0) {
            return;
        }
        log.warn("Leave read scope denied: user {} is neither requester nor active approver for request {}", currentUserId, leaveRequestId);
        throw new AccessDeniedException("Leave request is outside requester/approver scope.");
    }

    public void assertApprovalTaskApprover(long approvalTaskId) {
        if (isAdmin()) {
            return;
        }
        Long approverId = jdbcTemplate.query(
            "select approver_user_id from approval_task where id = ?",
            rs -> rs.next() ? (Long) rs.getObject(1) : null,
            approvalTaskId
        );
        if (approverId == null) {
            log.warn("Approval task scope denied: approvalTaskId {} not found", approvalTaskId);
            throw new AccessDeniedException("Approval task not found.");
        }
        assertActorUser(approverId);
    }

    public void assertTenderLocationScope(long tenderEntryId) {
        Long locationId = jdbcTemplate.query(
            """
            select bo.location_id
            from tender_entry te
            join booking_order bo on bo.id = te.booking_order_id
            where te.id = ?
            """,
            rs -> rs.next() ? (Long) rs.getObject(1) : null,
            tenderEntryId
        );
        if (locationId == null) {
            log.warn("Tender location scope denied: tenderEntryId {} not found", tenderEntryId);
            throw new AccessDeniedException("Tender entry not found.");
        }
        assertLocationAccess(locationId);
    }

    public void assertReconciliationExceptionScope(long exceptionId) {
        Long locationId = jdbcTemplate.query(
            """
            select rr.location_id
            from reconciliation_exception re
            join reconciliation_run rr on rr.id = re.run_id
            where re.id = ?
            """,
            rs -> rs.next() ? (Long) rs.getObject(1) : null,
            exceptionId
        );
        if (locationId == null) {
            log.warn("Reconciliation exception scope denied: exceptionId {} not found", exceptionId);
            throw new AccessDeniedException("Reconciliation exception not found.");
        }
        assertLocationAccess(locationId);
    }

    public void assertModerationCaseScope(long caseId) {
        Long locationId = jdbcTemplate.query(
            "select location_id from moderation_case where id = ?",
            rs -> rs.next() ? (Long) rs.getObject(1) : null,
            caseId
        );
        if (locationId == null) {
            log.warn("Moderation case scope denied: caseId {} not found", caseId);
            throw new AccessDeniedException("Moderation case not found.");
        }
        assertLocationAccess(locationId);
    }

    public void assertUserReportScope(long reportId) {
        Long locationId = jdbcTemplate.query(
            "select location_id from user_report where id = ?",
            rs -> rs.next() ? (Long) rs.getObject(1) : null,
            reportId
        );
        if (locationId == null) {
            log.warn("User report scope denied: reportId {} not found", reportId);
            throw new AccessDeniedException("User report not found.");
        }
        assertLocationAccess(locationId);
    }

    public Optional<Long> currentLocationScope() {
        Authentication auth = requireAuthentication();
        if (hasRole(auth, "ROLE_ADMIN")) {
            return Optional.empty();
        }
        if (hasRole(auth, "ROLE_DEVICE_SERVICE")) {
            Long deviceLocation = jdbcTemplate.query(
                "select location_id from device_client where client_key = ? and status = 'ACTIVE'",
                rs -> rs.next() ? (Long) rs.getObject(1) : null,
                auth.getName()
            );
            return Optional.ofNullable(deviceLocation);
        }
        Long userLocation = jdbcTemplate.query(
            "select location_id from app_user where lower(username)=lower(?) and is_active=true",
            rs -> rs.next() ? (Long) rs.getObject(1) : null,
            auth.getName()
        );
        return Optional.ofNullable(userLocation);
    }

    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && hasRole(auth, "ROLE_ADMIN");
    }

    private Authentication requireAuthentication() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new AccessDeniedException("Authentication is required.");
        }
        return auth;
    }

    private boolean hasRole(Authentication auth, String role) {
        for (GrantedAuthority authority : auth.getAuthorities()) {
            if (role.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
