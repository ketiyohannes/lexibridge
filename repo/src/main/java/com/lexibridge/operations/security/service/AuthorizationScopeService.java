package com.lexibridge.operations.security.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AuthorizationScopeService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationScopeService.class);

    private final AuthorizationIdentityService authorizationIdentityService;
    private final ScopeLookupService scopeLookupService;

    public AuthorizationScopeService(AuthorizationIdentityService authorizationIdentityService,
                                     ScopeLookupService scopeLookupService) {
        this.authorizationIdentityService = authorizationIdentityService;
        this.scopeLookupService = scopeLookupService;
    }

    public void assertLocationAccess(Long locationId) {
        if (locationId == null) {
            log.warn("Location scope denied: missing locationId");
            throw new AccessDeniedException("Location is required.");
        }

        if (authorizationIdentityService.isAdmin()) {
            return;
        }

        Authentication auth = authorizationIdentityService.requireAuthentication();
        if (authorizationIdentityService.hasRole(auth, "ROLE_DEVICE_SERVICE")) {
            Long deviceLocation = authorizationIdentityService.activeDeviceLocation(auth.getName());
            if (deviceLocation == null || !deviceLocation.equals(locationId)) {
                log.warn("Location scope denied for device client '{}' on location {}", auth.getName(), locationId);
                throw new AccessDeniedException("Requested location is outside device scope.");
            }
            return;
        }

        Long userLocation = authorizationIdentityService.activeUserLocation(auth.getName());
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

        if (authorizationIdentityService.isAdmin()) {
            return;
        }

        Authentication auth = authorizationIdentityService.requireAuthentication();
        if (authorizationIdentityService.hasRole(auth, "ROLE_DEVICE_SERVICE")) {
            Long actorLocation = scopeLookupService.activeUserLocationById(actorUserId);
            Long deviceLocation = authorizationIdentityService.activeDeviceLocation(auth.getName());
            if (actorLocation == null || deviceLocation == null || !deviceLocation.equals(actorLocation)) {
                log.warn("Actor scope denied for device '{}' to actorUserId {}", auth.getName(), actorUserId);
                throw new AccessDeniedException("Actor user is outside device location scope.");
            }
            return;
        }

        Long currentUserId = authorizationIdentityService.activeUserId(auth.getName());
        if (currentUserId == null || !currentUserId.equals(actorUserId)) {
            log.warn("Actor scope denied: principal '{}' does not match actorUserId {}", auth.getName(), actorUserId);
            throw new AccessDeniedException("Actor user does not match authenticated principal.");
        }
    }

    public long requireCurrentUserId() {
        return authorizationIdentityService.requireCurrentUserId();
    }

    public void assertBookingAccess(long bookingOrderId) {
        Long locationId = scopeLookupService.bookingLocation(bookingOrderId);
        if (locationId == null) {
            log.warn("Booking scope denied: bookingOrderId {} not found", bookingOrderId);
            throw new AccessDeniedException("Booking order not found.");
        }
        assertLocationAccess(locationId);
    }

    public void assertRefundScope(long refundId) {
        Long locationId = scopeLookupService.refundLocation(refundId);
        if (locationId == null) {
            log.warn("Refund scope denied: refundId {} not found", refundId);
            throw new AccessDeniedException("Refund request not found.");
        }
        assertLocationAccess(locationId);
    }

    public void assertReconciliationRunScope(long runId) {
        Long locationId = scopeLookupService.reconciliationRunLocation(runId);
        if (locationId == null) {
            log.warn("Reconciliation run scope denied: runId {} not found", runId);
            throw new AccessDeniedException("Reconciliation run not found.");
        }
        assertLocationAccess(locationId);
    }

    public void assertContentItemScope(long contentItemId) {
        Long locationId = scopeLookupService.contentItemLocation(contentItemId);
        if (locationId == null) {
            log.warn("Content item scope denied: contentItemId {} not found", contentItemId);
            throw new AccessDeniedException("Content item not found.");
        }
        assertLocationAccess(locationId);
    }

    public void assertLeaveRequestRequester(long leaveRequestId) {
        if (authorizationIdentityService.isAdmin()) {
            return;
        }
        Long requesterId = scopeLookupService.leaveRequester(leaveRequestId);
        if (requesterId == null) {
            log.warn("Leave requester scope denied: leaveRequestId {} not found", leaveRequestId);
            throw new AccessDeniedException("Leave request not found.");
        }
        assertActorUser(requesterId);
    }

    public void assertLeaveRequestReadAccess(long leaveRequestId) {
        if (authorizationIdentityService.isAdmin()) {
            return;
        }
        long currentUserId = requireCurrentUserId();
        Long requesterId = scopeLookupService.leaveRequester(leaveRequestId);
        if (requesterId == null) {
            log.warn("Leave read scope denied: leaveRequestId {} not found", leaveRequestId);
            throw new AccessDeniedException("Leave request not found.");
        }
        if (requesterId.equals(currentUserId)) {
            return;
        }
        if (scopeLookupService.activeApproverTaskCount(leaveRequestId, currentUserId) > 0) {
            return;
        }
        log.warn("Leave read scope denied: user {} is neither requester nor active approver for request {}", currentUserId, leaveRequestId);
        throw new AccessDeniedException("Leave request is outside requester/approver scope.");
    }

    public void assertApprovalTaskApprover(long approvalTaskId) {
        if (authorizationIdentityService.isAdmin()) {
            return;
        }
        Long approverId = scopeLookupService.approvalTaskApprover(approvalTaskId);
        if (approverId == null) {
            log.warn("Approval task scope denied: approvalTaskId {} not found", approvalTaskId);
            throw new AccessDeniedException("Approval task not found.");
        }
        assertActorUser(approverId);
    }

    public void assertTenderLocationScope(long tenderEntryId) {
        Long locationId = scopeLookupService.tenderLocation(tenderEntryId);
        if (locationId == null) {
            log.warn("Tender location scope denied: tenderEntryId {} not found", tenderEntryId);
            throw new AccessDeniedException("Tender entry not found.");
        }
        assertLocationAccess(locationId);
    }

    public void assertReconciliationExceptionScope(long exceptionId) {
        Long locationId = scopeLookupService.reconciliationExceptionLocation(exceptionId);
        if (locationId == null) {
            log.warn("Reconciliation exception scope denied: exceptionId {} not found", exceptionId);
            throw new AccessDeniedException("Reconciliation exception not found.");
        }
        assertLocationAccess(locationId);
    }

    public void assertModerationCaseScope(long caseId) {
        Long locationId = scopeLookupService.moderationCaseLocation(caseId);
        if (locationId == null) {
            log.warn("Moderation case scope denied: caseId {} not found", caseId);
            throw new AccessDeniedException("Moderation case not found.");
        }
        assertLocationAccess(locationId);
    }

    public void assertUserReportScope(long reportId) {
        Long locationId = scopeLookupService.userReportLocation(reportId);
        if (locationId == null) {
            log.warn("User report scope denied: reportId {} not found", reportId);
            throw new AccessDeniedException("User report not found.");
        }
        assertLocationAccess(locationId);
    }

    public Optional<Long> currentLocationScope() {
        Authentication auth = authorizationIdentityService.requireAuthentication();
        if (authorizationIdentityService.hasRole(auth, "ROLE_ADMIN")) {
            return Optional.empty();
        }
        if (authorizationIdentityService.hasRole(auth, "ROLE_DEVICE_SERVICE")) {
            Long deviceLocation = authorizationIdentityService.activeDeviceLocation(auth.getName());
            return Optional.ofNullable(deviceLocation);
        }
        Long userLocation = authorizationIdentityService.activeUserLocation(auth.getName());
        return Optional.ofNullable(userLocation);
    }
}
