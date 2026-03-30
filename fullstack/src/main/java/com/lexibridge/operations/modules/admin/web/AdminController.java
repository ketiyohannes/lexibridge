package com.lexibridge.operations.modules.admin.web;

import com.lexibridge.operations.modules.admin.service.WebhookSecurityService;
import com.lexibridge.operations.modules.admin.service.AdminUserManagementService;
import com.lexibridge.operations.monitoring.TracePersistenceService;
import com.lexibridge.operations.security.privacy.PiiMaskingService;
import com.lexibridge.operations.security.service.AuthorizationScopeService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
public class AdminController {

    private final WebhookSecurityService webhookSecurityService;
    private final AdminUserManagementService adminUserManagementService;
    private final TracePersistenceService tracePersistenceService;
    private final PiiMaskingService piiMaskingService;
    private final AuthorizationScopeService authorizationScopeService;

    public AdminController(WebhookSecurityService webhookSecurityService,
                           AdminUserManagementService adminUserManagementService,
                           TracePersistenceService tracePersistenceService,
                           PiiMaskingService piiMaskingService,
                           AuthorizationScopeService authorizationScopeService) {
        this.webhookSecurityService = webhookSecurityService;
        this.adminUserManagementService = adminUserManagementService;
        this.tracePersistenceService = tracePersistenceService;
        this.piiMaskingService = piiMaskingService;
        this.authorizationScopeService = authorizationScopeService;
    }

    @GetMapping("/portal/admin")
    public String admin(Model model) {
        populateModel(model);
        if (!model.containsAttribute("registerWebhookForm")) {
            model.addAttribute("registerWebhookForm", RegisterWebhookForm.defaults());
        }
        if (!model.containsAttribute("deliveryCheckForm")) {
            model.addAttribute("deliveryCheckForm", DeliveryCheckForm.defaults());
        }
        if (!model.containsAttribute("createUserForm")) {
            model.addAttribute("createUserForm", CreateUserForm.defaults());
        }
        if (!model.containsAttribute("updateUserForm")) {
            model.addAttribute("updateUserForm", UpdateUserForm.defaults());
        }
        if (!model.containsAttribute("roleActionForm")) {
            model.addAttribute("roleActionForm", RoleActionForm.defaults());
        }
        return "portal/admin";
    }

    @PostMapping("/portal/admin/webhooks")
    public String registerWebhook(@ModelAttribute RegisterWebhookForm form, Model model) {
        populateModel(model);
        model.addAttribute("registerWebhookForm", form);
        model.addAttribute("deliveryCheckForm", DeliveryCheckForm.defaults());
        model.addAttribute("createUserForm", CreateUserForm.defaults());
        model.addAttribute("updateUserForm", UpdateUserForm.defaults());
        model.addAttribute("roleActionForm", RoleActionForm.defaults());

        if (form.getLocationId() == null || form.getLocationId() <= 0 || form.getName() == null || form.getName().isBlank() || form.getCallbackUrl() == null || form.getCallbackUrl().isBlank()) {
            model.addAttribute("adminError", "Location, name, and callback URL are required.");
            return "portal/admin";
        }

        try {
            long webhookId = webhookSecurityService.register(form.getLocationId(), form.getName(), form.getCallbackUrl());
            model.addAttribute("registerResult", Map.of("webhookId", webhookId, "status", "REGISTERED"));
            model.addAttribute("adminSuccess", "Webhook registered successfully.");
            DeliveryCheckForm deliveryCheckForm = DeliveryCheckForm.defaults();
            deliveryCheckForm.setWebhookId(webhookId);
            model.addAttribute("deliveryCheckForm", deliveryCheckForm);
        } catch (RuntimeException ex) {
            model.addAttribute("adminError", ex.getMessage());
        }
        return "portal/admin";
    }

    @PostMapping("/portal/admin/webhooks/can-deliver")
    public String canDeliver(@ModelAttribute DeliveryCheckForm form, Model model) {
        populateModel(model);
        model.addAttribute("registerWebhookForm", RegisterWebhookForm.defaults());
        model.addAttribute("deliveryCheckForm", form);
        model.addAttribute("createUserForm", CreateUserForm.defaults());
        model.addAttribute("updateUserForm", UpdateUserForm.defaults());
        model.addAttribute("roleActionForm", RoleActionForm.defaults());

        if (form.getWebhookId() == null || form.getWebhookId() <= 0) {
            model.addAttribute("adminError", "Webhook ID must be a positive number.");
            return "portal/admin";
        }

        try {
            boolean allowed = webhookSecurityService.canDeliver(form.getWebhookId());
            model.addAttribute("deliveryCheckResult", Map.of("webhookId", form.getWebhookId(), "allowed", allowed));
            model.addAttribute("adminSuccess", "Webhook delivery policy check completed.");
        } catch (RuntimeException ex) {
            model.addAttribute("adminError", ex.getMessage());
        }
        return "portal/admin";
    }

    @PostMapping("/portal/admin/users")
    public String createUser(@ModelAttribute CreateUserForm form, Model model) {
        populateModel(model);
        model.addAttribute("registerWebhookForm", RegisterWebhookForm.defaults());
        model.addAttribute("deliveryCheckForm", DeliveryCheckForm.defaults());
        model.addAttribute("createUserForm", form);
        model.addAttribute("updateUserForm", UpdateUserForm.defaults());
        model.addAttribute("roleActionForm", RoleActionForm.defaults());

        if (form.getUsername() == null || form.getUsername().isBlank() ||
            form.getFullName() == null || form.getFullName().isBlank() ||
            form.getPassword() == null || form.getPassword().isBlank()) {
            model.addAttribute("adminError", "Username, full name, and password are required.");
            return "portal/admin";
        }

        try {
            Map<String, Object> result = adminUserManagementService.createUser(
                form.getLocationId(),
                form.getUsername(),
                form.getFullName(),
                form.getEmail(),
                form.getPassword(),
                form.getActive(),
                parseRolesCsv(form.getRolesCsv()),
                currentUserId()
            );
            model.addAttribute("userCreateResult", result);
            model.addAttribute("adminSuccess", "User created.");
        } catch (RuntimeException ex) {
            model.addAttribute("adminError", ex.getMessage());
        }
        return "portal/admin";
    }

    @PostMapping("/portal/admin/users/{userId}")
    public String updateUser(@PathVariable long userId,
                             @ModelAttribute UpdateUserForm form,
                             Model model) {
        populateModel(model);
        model.addAttribute("registerWebhookForm", RegisterWebhookForm.defaults());
        model.addAttribute("deliveryCheckForm", DeliveryCheckForm.defaults());
        model.addAttribute("createUserForm", CreateUserForm.defaults());
        model.addAttribute("updateUserForm", form);
        model.addAttribute("roleActionForm", RoleActionForm.defaults());

        if (form.getFullName() == null || form.getFullName().isBlank()) {
            model.addAttribute("adminError", "Full name is required.");
            return "portal/admin";
        }

        try {
            Map<String, Object> result = adminUserManagementService.updateUser(
                userId,
                form.getLocationId(),
                form.getFullName(),
                form.getEmail(),
                form.getActive(),
                currentUserId()
            );
            model.addAttribute("userUpdateResult", result);
            model.addAttribute("adminSuccess", "User updated.");
        } catch (RuntimeException ex) {
            model.addAttribute("adminError", ex.getMessage());
        }
        return "portal/admin";
    }

    @PostMapping("/portal/admin/users/{userId}/roles/assign")
    public String assignRole(@PathVariable long userId,
                             @ModelAttribute RoleActionForm form,
                             Model model) {
        return applyRoleAction(userId, form, model, true);
    }

    @PostMapping("/portal/admin/users/{userId}/roles/remove")
    public String removeRole(@PathVariable long userId,
                             @ModelAttribute RoleActionForm form,
                             Model model) {
        return applyRoleAction(userId, form, model, false);
    }

    private String applyRoleAction(long userId,
                                   RoleActionForm form,
                                   Model model,
                                   boolean assign) {
        populateModel(model);
        model.addAttribute("registerWebhookForm", RegisterWebhookForm.defaults());
        model.addAttribute("deliveryCheckForm", DeliveryCheckForm.defaults());
        model.addAttribute("createUserForm", CreateUserForm.defaults());
        model.addAttribute("updateUserForm", UpdateUserForm.defaults());
        model.addAttribute("roleActionForm", form);

        if (form.getRoleCode() == null || form.getRoleCode().isBlank()) {
            model.addAttribute("adminError", "Role code is required.");
            return "portal/admin";
        }

        try {
            Map<String, Object> result = assign
                ? adminUserManagementService.assignRole(userId, form.getRoleCode(), currentUserId())
                : adminUserManagementService.removeRole(userId, form.getRoleCode(), currentUserId());
            model.addAttribute("roleActionResult", result);
            model.addAttribute("adminSuccess", assign ? "Role assigned." : "Role removed.");
        } catch (RuntimeException ex) {
            model.addAttribute("adminError", ex.getMessage());
        }
        return "portal/admin";
    }

    private void populateModel(Model model) {
        model.addAttribute("webhooks", webhookSecurityService.activeWebhooks());
        model.addAttribute("users", maskedUsers(adminUserManagementService.users(100)));
        model.addAttribute("traces", tracePersistenceService.latest(100));
        model.addAttribute("nonceSample", webhookSecurityService.generateNonce());
    }

    private List<Map<String, Object>> maskedUsers(List<Map<String, Object>> users) {
        return users.stream().map(user -> {
            Map<String, Object> copy = new LinkedHashMap<>(user);
            Object email = copy.get("email");
            if (email != null) {
                copy.put("email", piiMaskingService.maskEmail(String.valueOf(email)));
            }
            Object fullName = copy.get("full_name");
            if (fullName != null) {
                copy.put("full_name", piiMaskingService.maskName(String.valueOf(fullName)));
            }
            return copy;
        }).toList();
    }

    private long currentUserId() {
        return authorizationScopeService.requireCurrentUserId();
    }

    private List<String> parseRolesCsv(String rolesCsv) {
        if (rolesCsv == null || rolesCsv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(rolesCsv.split(","))
            .map(String::trim)
            .filter(v -> !v.isBlank())
            .toList();
    }

    public static final class RegisterWebhookForm {
        private Long locationId;
        private String name;
        private String callbackUrl;

        public static RegisterWebhookForm defaults() {
            RegisterWebhookForm form = new RegisterWebhookForm();
            form.setLocationId(1L);
            return form;
        }

        public Long getLocationId() { return locationId; }
        public void setLocationId(Long locationId) { this.locationId = locationId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getCallbackUrl() { return callbackUrl; }
        public void setCallbackUrl(String callbackUrl) { this.callbackUrl = callbackUrl; }
    }

    public static final class DeliveryCheckForm {
        private Long webhookId;

        public static DeliveryCheckForm defaults() {
            return new DeliveryCheckForm();
        }

        public Long getWebhookId() { return webhookId; }
        public void setWebhookId(Long webhookId) { this.webhookId = webhookId; }
    }

    public static final class CreateUserForm {
        private Long locationId;
        private String username;
        private String fullName;
        private String email;
        private String password;
        private Boolean active;
        private String rolesCsv;

        public static CreateUserForm defaults() {
            CreateUserForm form = new CreateUserForm();
            form.setLocationId(1L);
            form.setActive(true);
            form.setRolesCsv("EMPLOYEE");
            return form;
        }

        public Long getLocationId() { return locationId; }
        public void setLocationId(Long locationId) { this.locationId = locationId; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getFullName() { return fullName; }
        public void setFullName(String fullName) { this.fullName = fullName; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public Boolean getActive() { return active != null && active; }
        public void setActive(Boolean active) { this.active = active; }
        public String getRolesCsv() { return rolesCsv; }
        public void setRolesCsv(String rolesCsv) { this.rolesCsv = rolesCsv; }
    }

    public static final class UpdateUserForm {
        private Long userId;
        private Long locationId;
        private String fullName;
        private String email;
        private Boolean active;

        public static UpdateUserForm defaults() {
            UpdateUserForm form = new UpdateUserForm();
            form.setUserId(1L);
            form.setLocationId(1L);
            form.setActive(true);
            return form;
        }

        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
        public Long getLocationId() { return locationId; }
        public void setLocationId(Long locationId) { this.locationId = locationId; }
        public String getFullName() { return fullName; }
        public void setFullName(String fullName) { this.fullName = fullName; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public Boolean getActive() { return active != null && active; }
        public void setActive(Boolean active) { this.active = active; }
    }

    public static final class RoleActionForm {
        private String roleCode;

        public static RoleActionForm defaults() {
            RoleActionForm form = new RoleActionForm();
            form.setRoleCode("EMPLOYEE");
            return form;
        }

        public String getRoleCode() { return roleCode; }
        public void setRoleCode(String roleCode) { this.roleCode = roleCode; }
    }
}
