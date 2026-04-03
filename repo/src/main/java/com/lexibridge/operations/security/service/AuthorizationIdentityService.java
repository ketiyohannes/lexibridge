package com.lexibridge.operations.security.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class AuthorizationIdentityService {

    private final JdbcTemplate jdbcTemplate;

    public AuthorizationIdentityService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Authentication requireAuthentication() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new AccessDeniedException("Authentication is required.");
        }
        return auth;
    }

    public boolean hasRole(Authentication auth, String role) {
        for (GrantedAuthority authority : auth.getAuthorities()) {
            if (role.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    public boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && hasRole(auth, "ROLE_ADMIN");
    }

    public Long activeDeviceLocation(String clientKey) {
        return jdbcTemplate.query(
            "select location_id from device_client where client_key = ? and status = 'ACTIVE'",
            rs -> rs.next() ? (Long) rs.getObject(1) : null,
            clientKey
        );
    }

    public Long activeUserLocation(String username) {
        return jdbcTemplate.query(
            "select location_id from app_user where lower(username)=lower(?) and is_active=true",
            rs -> rs.next() ? (Long) rs.getObject(1) : null,
            username
        );
    }

    public Long activeUserId(String username) {
        return jdbcTemplate.query(
            "select id from app_user where lower(username)=lower(?) and is_active=true",
            rs -> rs.next() ? (Long) rs.getObject(1) : null,
            username
        );
    }

    public long requireCurrentUserId() {
        Authentication auth = requireAuthentication();
        if (hasRole(auth, "ROLE_DEVICE_SERVICE")) {
            throw new AccessDeniedException("Device clients do not map to a human user ID.");
        }
        Long currentUserId = activeUserId(auth.getName());
        if (currentUserId == null) {
            throw new AccessDeniedException("Authenticated user not found.");
        }
        return currentUserId;
    }
}
