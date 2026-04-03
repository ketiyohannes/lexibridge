package com.lexibridge.operations.modules.admin.api;

import com.lexibridge.operations.modules.admin.service.WebhookSecurityService;
import com.lexibridge.operations.modules.admin.service.AdminUserManagementService;
import com.lexibridge.operations.modules.admin.service.DeviceHmacKeyRotationService;
import com.lexibridge.operations.modules.admin.service.WebhookDeliveryService;
import com.lexibridge.operations.monitoring.TracePersistenceService;
import com.lexibridge.operations.security.service.AuthorizationScopeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminApiController {

    private final WebhookSecurityService webhookSecurityService;
    private final WebhookDeliveryService webhookDeliveryService;
    private final AdminUserManagementService adminUserManagementService;
    private final DeviceHmacKeyRotationService deviceHmacKeyRotationService;
    private final TracePersistenceService tracePersistenceService;
    private final AuthorizationScopeService authorizationScopeService;

    public AdminApiController(WebhookSecurityService webhookSecurityService,
                              WebhookDeliveryService webhookDeliveryService,
                              AdminUserManagementService adminUserManagementService,
                              DeviceHmacKeyRotationService deviceHmacKeyRotationService,
                              TracePersistenceService tracePersistenceService,
                              AuthorizationScopeService authorizationScopeService) {
        this.webhookSecurityService = webhookSecurityService;
        this.webhookDeliveryService = webhookDeliveryService;
        this.adminUserManagementService = adminUserManagementService;
        this.deviceHmacKeyRotationService = deviceHmacKeyRotationService;
        this.tracePersistenceService = tracePersistenceService;
        this.authorizationScopeService = authorizationScopeService;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("status", "ok", "module", "admin");
    }

    @PostMapping("/webhooks")
    public Map<String, Object> registerWebhook(@Valid @RequestBody RegisterWebhookCommand command) {
        authorizationScopeService.assertLocationAccess(command.locationId);
        long id = webhookSecurityService.register(command.locationId, command.name, command.callbackUrl, command.allowedCidr);
        return Map.of("webhookId", id, "status", "REGISTERED");
    }

    @GetMapping("/webhooks/can-deliver")
    public Map<String, Object> canDeliver(@RequestParam long webhookId) {
        return Map.of("webhookId", webhookId, "allowed", webhookSecurityService.canDeliver(webhookId));
    }

    @GetMapping("/webhooks")
    public List<Map<String, Object>> webhooks() {
        return webhookSecurityService.activeWebhooks();
    }

    @PostMapping("/webhooks/dispatch")
    public Map<String, Object> dispatchToLocation(@Valid @RequestBody DispatchWebhookCommand command) {
        authorizationScopeService.assertLocationAccess(command.locationId);
        long actorUserId = authorizationScopeService.requireCurrentUserId();
        return webhookDeliveryService.dispatchToLocation(command.locationId, command.eventType, command.payload, actorUserId);
    }

    @PostMapping("/webhooks/{webhookId}/dispatch")
    public Map<String, Object> dispatchToWebhook(@org.springframework.web.bind.annotation.PathVariable long webhookId,
                                                 @Valid @RequestBody DispatchSingleWebhookCommand command) {
        long actorUserId = authorizationScopeService.requireCurrentUserId();
        return webhookDeliveryService.dispatchToWebhook(webhookId, command.eventType, command.payload, actorUserId);
    }

    @GetMapping("/users")
    public List<Map<String, Object>> users(@RequestParam(defaultValue = "100") int limit) {
        return adminUserManagementService.users(limit);
    }

    @GetMapping("/traces")
    public List<Map<String, Object>> traces(@RequestParam(defaultValue = "100") int limit) {
        return tracePersistenceService.latest(limit);
    }

    @PostMapping("/users")
    public Map<String, Object> createUser(@Valid @RequestBody CreateUserCommand command) {
        long actorUserId = authorizationScopeService.requireCurrentUserId();
        return adminUserManagementService.createUser(
            command.locationId,
            command.username,
            command.fullName,
            command.email,
            command.password,
            command.active == null || command.active,
            command.roles,
            actorUserId
        );
    }

    @PostMapping("/users/{userId}")
    public Map<String, Object> updateUser(@org.springframework.web.bind.annotation.PathVariable long userId,
                                          @Valid @RequestBody UpdateUserCommand command) {
        long actorUserId = authorizationScopeService.requireCurrentUserId();
        return adminUserManagementService.updateUser(
            userId,
            command.locationId,
            command.fullName,
            command.email,
            command.active == null || command.active,
            actorUserId
        );
    }

    @PostMapping("/users/{userId}/roles/assign")
    public Map<String, Object> assignRole(@org.springframework.web.bind.annotation.PathVariable long userId,
                                          @Valid @RequestBody RoleCommand command) {
        long actorUserId = authorizationScopeService.requireCurrentUserId();
        return adminUserManagementService.assignRole(userId, command.roleCode, actorUserId);
    }

    @PostMapping("/users/{userId}/roles/remove")
    public Map<String, Object> removeRole(@org.springframework.web.bind.annotation.PathVariable long userId,
                                          @Valid @RequestBody RoleCommand command) {
        long actorUserId = authorizationScopeService.requireCurrentUserId();
        return adminUserManagementService.removeRole(userId, command.roleCode, actorUserId);
    }

    @PostMapping("/users/{userId}/email/reveal")
    public Map<String, Object> revealEmail(@org.springframework.web.bind.annotation.PathVariable long userId,
                                           @Valid @RequestBody EmailRevealCommand command) {
        long actorUserId = authorizationScopeService.requireCurrentUserId();
        return adminUserManagementService.revealUserEmail(userId, command.reason, actorUserId);
    }

    @GetMapping("/device-clients/{clientKey}/hmac/keys")
    public List<Map<String, Object>> hmacKeyInventory(@org.springframework.web.bind.annotation.PathVariable String clientKey) {
        return deviceHmacKeyRotationService.keyInventory(clientKey);
    }

    @PostMapping("/device-clients/{clientKey}/hmac/rotate")
    public Map<String, Object> rotateHmacKey(@org.springframework.web.bind.annotation.PathVariable String clientKey,
                                             @Valid @RequestBody RotateHmacKeyCommand command) {
        long actorUserId = authorizationScopeService.requireCurrentUserId();
        return deviceHmacKeyRotationService.rotate(
            clientKey,
            command.sharedSecret,
            command.overlapMinutes == null ? 30 : command.overlapMinutes,
            command.reason,
            actorUserId
        );
    }

    @PostMapping("/device-clients/{clientKey}/hmac/cutover")
    public Map<String, Object> cutoverHmacKey(@org.springframework.web.bind.annotation.PathVariable String clientKey,
                                              @Valid @RequestBody CutoverHmacKeyCommand command) {
        long actorUserId = authorizationScopeService.requireCurrentUserId();
        return deviceHmacKeyRotationService.cutover(
            clientKey,
            command.activeKeyVersion,
            command.reason,
            actorUserId
        );
    }

    public static class RegisterWebhookCommand {
        @NotNull
        public Long locationId;
        @NotBlank
        public String name;
        @NotBlank
        public String callbackUrl;
        public String allowedCidr;
    }

    public static class CreateUserCommand {
        public Long locationId;
        @NotBlank
        public String username;
        @NotBlank
        public String fullName;
        public String email;
        @NotBlank
        public String password;
        public Boolean active;
        @NotNull
        public List<String> roles;
    }

    public static class DispatchWebhookCommand {
        @NotNull
        public Long locationId;
        @NotBlank
        public String eventType;
        public Map<String, Object> payload;
    }

    public static class DispatchSingleWebhookCommand {
        @NotBlank
        public String eventType;
        public Map<String, Object> payload;
    }

    public static class UpdateUserCommand {
        public Long locationId;
        @NotBlank
        public String fullName;
        public String email;
        public Boolean active;
    }

    public static class RoleCommand {
        @NotBlank
        public String roleCode;
    }

    public static class EmailRevealCommand {
        @NotBlank
        public String reason;
    }

    public static class RotateHmacKeyCommand {
        @NotBlank
        public String sharedSecret;
        public Integer overlapMinutes;
        @NotBlank
        public String reason;
    }

    public static class CutoverHmacKeyCommand {
        @NotNull
        public Integer activeKeyVersion;
        @NotBlank
        public String reason;
    }
}
