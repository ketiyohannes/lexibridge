package com.lexibridge.operations.modules.admin.service;

import com.lexibridge.operations.governance.AuditLogService;
import com.lexibridge.operations.modules.admin.repository.AdminUserRepository;
import com.lexibridge.operations.security.privacy.FieldEncryptionService;
import com.lexibridge.operations.security.service.PasswordPolicyValidator;
import jakarta.transaction.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminUserManagementService {

    private final AdminUserRepository adminUserRepository;
    private final PasswordPolicyValidator passwordPolicyValidator;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;
    private final FieldEncryptionService fieldEncryptionService;

    public AdminUserManagementService(AdminUserRepository adminUserRepository,
                                      PasswordPolicyValidator passwordPolicyValidator,
                                      PasswordEncoder passwordEncoder,
                                      AuditLogService auditLogService,
                                      FieldEncryptionService fieldEncryptionService) {
        this.adminUserRepository = adminUserRepository;
        this.passwordPolicyValidator = passwordPolicyValidator;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
        this.fieldEncryptionService = fieldEncryptionService;
    }

    public List<Map<String, Object>> users(int limit) {
        int effectiveLimit = limit <= 0 ? 100 : Math.min(limit, 500);
        return adminUserRepository.usersWithRoles(effectiveLimit)
            .stream()
            .map(row -> {
                Map<String, Object> mapped = new LinkedHashMap<>(row);
                String encryptedEmail = row.get("email") == null ? null : String.valueOf(row.get("email"));
                mapped.put("email", fieldEncryptionService.decryptString(encryptedEmail));
                String roleCodes = (String) row.get("role_codes");
                mapped.put("roles", roleCodes == null || roleCodes.isBlank() ? List.of() : List.of(roleCodes.split(",")));
                return mapped;
            })
            .toList();
    }

    @Transactional
    public Map<String, Object> createUser(Long locationId,
                                          String username,
                                          String fullName,
                                          String email,
                                          String rawPassword,
                                          boolean active,
                                          List<String> roleCodes,
                                          long actorUserId) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username is required.");
        }
        if (fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("Full name is required.");
        }
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("Password is required.");
        }
        if (roleCodes == null || roleCodes.isEmpty()) {
            throw new IllegalArgumentException("At least one role is required.");
        }

        passwordPolicyValidator.validateOrThrow(rawPassword);

        long userId;
        try {
            userId = adminUserRepository.createUser(
                locationId,
                username.trim(),
                fullName.trim(),
                email == null || email.isBlank() ? null : fieldEncryptionService.encryptString(email.trim()),
                passwordEncoder.encode(rawPassword),
                active
            );
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalArgumentException("Username already exists or user payload is invalid.", ex);
        }

        for (String roleCode : roleCodes) {
            assignRoleInternal(userId, roleCode);
        }

        List<String> assigned = adminUserRepository.roleCodesForUser(userId);
        auditLogService.logUserEvent(
            actorUserId,
            "ADMIN_USER_CREATED",
            "app_user",
            String.valueOf(userId),
            locationId,
            Map.of("username", username.trim(), "roles", assigned, "active", active)
        );
        return Map.of("userId", userId, "status", active ? "ACTIVE" : "INACTIVE", "roles", assigned);
    }

    @Transactional
    public Map<String, Object> updateUser(long userId,
                                          Long locationId,
                                          String fullName,
                                          String email,
                                          boolean active,
                                          long actorUserId) {
        if (fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("Full name is required.");
        }
        int updated = adminUserRepository.updateUser(
            userId,
            locationId,
            fullName.trim(),
            email == null || email.isBlank() ? null : fieldEncryptionService.encryptString(email.trim()),
            active
        );
        if (updated == 0) {
            throw new IllegalArgumentException("User not found.");
        }
        auditLogService.logUserEvent(
            actorUserId,
            "ADMIN_USER_UPDATED",
            "app_user",
            String.valueOf(userId),
            locationId,
            Map.of("active", active)
        );
        return Map.of("userId", userId, "status", active ? "ACTIVE" : "INACTIVE");
    }

    @Transactional
    public Map<String, Object> assignRole(long userId, String roleCode, long actorUserId) {
        ensureUser(userId);
        assignRoleInternal(userId, roleCode);
        List<String> roles = adminUserRepository.roleCodesForUser(userId);
        auditLogService.logUserEvent(
            actorUserId,
            "ADMIN_USER_ROLE_ASSIGNED",
            "app_user",
            String.valueOf(userId),
            null,
            Map.of("roleCode", roleCode, "roles", roles)
        );
        return Map.of("userId", userId, "roles", roles);
    }

    @Transactional
    public Map<String, Object> removeRole(long userId, String roleCode, long actorUserId) {
        ensureUser(userId);
        Long roleId = adminUserRepository.roleIdByCode(roleCode);
        if (roleId == null) {
            throw new IllegalArgumentException("Role not found.");
        }
        adminUserRepository.removeRole(userId, roleId);
        List<String> roles = adminUserRepository.roleCodesForUser(userId);
        auditLogService.logUserEvent(
            actorUserId,
            "ADMIN_USER_ROLE_REMOVED",
            "app_user",
            String.valueOf(userId),
            null,
            Map.of("roleCode", roleCode, "roles", roles)
        );
        return Map.of("userId", userId, "roles", roles);
    }

    private void ensureUser(long userId) {
        if (!adminUserRepository.userExists(userId)) {
            throw new IllegalArgumentException("User not found.");
        }
    }

    private void assignRoleInternal(long userId, String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            throw new IllegalArgumentException("Role code is required.");
        }
        Long roleId = adminUserRepository.roleIdByCode(roleCode.trim().toUpperCase());
        if (roleId == null) {
            throw new IllegalArgumentException("Role not found: " + roleCode);
        }
        adminUserRepository.assignRole(userId, roleId);
    }
}
