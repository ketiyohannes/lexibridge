package com.lexibridge.operations.modules.admin.service;

import com.lexibridge.operations.governance.AuditLogService;
import com.lexibridge.operations.modules.admin.repository.AdminUserRepository;
import com.lexibridge.operations.security.privacy.FieldEncryptionService;
import com.lexibridge.operations.security.privacy.PiiMaskingService;
import com.lexibridge.operations.security.service.PasswordPolicyValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserManagementServiceTest {

    @Mock
    private AdminUserRepository adminUserRepository;
    @Mock
    private PasswordPolicyValidator passwordPolicyValidator;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private FieldEncryptionService fieldEncryptionService;
    @Mock
    private PiiMaskingService piiMaskingService;

    private AdminUserManagementService service;

    @BeforeEach
    void setUp() {
        service = new AdminUserManagementService(
            adminUserRepository,
            passwordPolicyValidator,
            passwordEncoder,
            auditLogService,
            fieldEncryptionService,
            piiMaskingService
        );
    }

    @Test
    void createUser_shouldCreateAndAssignRoles() {
        when(passwordEncoder.encode("StrongPass123!@")).thenReturn("hash");
        when(fieldEncryptionService.encryptString("new@x.com")).thenReturn("enc:new@x.com");
        when(adminUserRepository.createUser(any(), eq("new.user"), eq("New User"), eq("enc:new@x.com"), eq("hash"), anyBoolean())).thenReturn(55L);
        when(adminUserRepository.roleIdByCode("EMPLOYEE")).thenReturn(1L);
        when(adminUserRepository.roleCodesForUser(55L)).thenReturn(List.of("EMPLOYEE"));

        Map<String, Object> result = service.createUser(
            1L,
            "new.user",
            "New User",
            "new@x.com",
            "StrongPass123!@",
            true,
            List.of("EMPLOYEE"),
            9L
        );

        assertEquals(55L, result.get("userId"));
        verify(adminUserRepository).assignRole(55L, 1L);
    }

    @Test
    void assignRole_shouldRejectUnknownRole() {
        when(adminUserRepository.userExists(55L)).thenReturn(true);
        when(adminUserRepository.roleIdByCode("UNKNOWN")).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.assignRole(55L, "UNKNOWN", 1L));
    }

    @Test
    void updateUser_shouldReturnInactiveStatus() {
        when(fieldEncryptionService.encryptString("x@y.com")).thenReturn("enc:x@y.com");
        when(adminUserRepository.updateUser(eq(10L), any(), anyString(), any(), eq(false))).thenReturn(1);

        Map<String, Object> result = service.updateUser(10L, 1L, "User", "x@y.com", false, 1L);

        assertEquals("INACTIVE", result.get("status"));
    }

    @Test
    void users_shouldMaskPiiBeforeReturning() {
        when(adminUserRepository.usersWithRoles(100)).thenReturn(List.of(Map.of(
            "id", 7L,
            "username", "managed.user",
            "full_name", "Managed User",
            "email", "enc:abc",
            "role_codes", "EMPLOYEE"
        )));
        when(fieldEncryptionService.decryptString("enc:abc")).thenReturn("managed@example.com");
        when(piiMaskingService.maskEmail("managed@example.com")).thenReturn("m***@example.com");
        when(piiMaskingService.maskName("Managed User")).thenReturn("M**** U***");

        List<Map<String, Object>> users = service.users(100);

        assertEquals("m***@example.com", users.getFirst().get("email"));
        assertEquals("M**** U***", users.getFirst().get("full_name"));
    }

    @Test
    void revealUserEmail_shouldRequireReasonAndAudit() {
        when(adminUserRepository.userById(9L)).thenReturn(Map.of(
            "id", 9L,
            "location_id", 1L,
            "username", "managed.user",
            "email", "enc:abc"
        ));
        when(fieldEncryptionService.decryptString("enc:abc")).thenReturn("managed@example.com");

        Map<String, Object> result = service.revealUserEmail(9L, "Compliance review", 3L);

        assertEquals("managed@example.com", result.get("email"));
        verify(auditLogService).logUserEvent(eq(3L), eq("ADMIN_USER_EMAIL_REVEALED"), eq("app_user"), eq("9"), eq(1L), any());
    }
}
