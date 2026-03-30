package com.lexibridge.operations.security.service;

import com.lexibridge.operations.security.repository.SecurityBootstrapRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class SecurityBootstrapService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SecurityBootstrapService.class);

    private final SecurityBootstrapRepository bootstrapRepository;
    private final PasswordPolicyValidator passwordPolicyValidator;
    private final PasswordEncoder passwordEncoder;

    @Value("${lexibridge.bootstrap.enabled:false}")
    private boolean bootstrapEnabled;

    @Value("${lexibridge.bootstrap.admin.username:admin}")
    private String adminUsername;

    @Value("${lexibridge.bootstrap.admin.full-name:LexiBridge Admin}")
    private String adminFullName;

    @Value("${lexibridge.bootstrap.admin.password:}")
    private String adminPassword;

    @Value("${lexibridge.bootstrap.device.client-key:demo-device}")
    private String deviceClientKey;

    @Value("${lexibridge.bootstrap.device.shared-secret:}")
    private String deviceSharedSecret;

    public SecurityBootstrapService(SecurityBootstrapRepository bootstrapRepository,
                                    PasswordPolicyValidator passwordPolicyValidator,
                                    PasswordEncoder passwordEncoder) {
        this.bootstrapRepository = bootstrapRepository;
        this.passwordPolicyValidator = passwordPolicyValidator;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (!bootstrapEnabled) {
            return;
        }
        validateBootstrapSecrets();
        bootstrapAdminUser();
        bootstrapDeviceClient();
    }

    private void validateBootstrapSecrets() {
        if (adminPassword == null || adminPassword.isBlank()) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_PASSWORD is required when bootstrap is enabled.");
        }
        if (deviceSharedSecret == null || deviceSharedSecret.isBlank()) {
            throw new IllegalStateException("BOOTSTRAP_DEVICE_SHARED_SECRET is required when bootstrap is enabled.");
        }
        if ("ChangeMe123!@".equals(adminPassword) || "demo-device-shared-secret".equals(deviceSharedSecret)) {
            throw new IllegalStateException("Bootstrap secrets must be rotated and may not use known insecure defaults.");
        }
    }

    private void bootstrapAdminUser() {
        if (bootstrapRepository.findUserIdByUsername(adminUsername).isPresent()) {
            return;
        }

        passwordPolicyValidator.validateOrThrow(adminPassword);

        long userId = bootstrapRepository.createAdminUser(
            adminUsername,
            adminFullName,
            passwordEncoder.encode(adminPassword)
        );

        long adminRoleId = bootstrapRepository.findRoleIdByCode("ADMIN")
            .orElseThrow(() -> new IllegalStateException("ADMIN role not found in bootstrap migration."));

        bootstrapRepository.assignRole(userId, adminRoleId);
        log.warn("Bootstrapped default admin user '{}'. Change password immediately.", adminUsername);
    }

    private void bootstrapDeviceClient() {
        if (bootstrapRepository.findDeviceClientIdByKey(deviceClientKey).isPresent()) {
            return;
        }

        long clientId = bootstrapRepository.createDeviceClient(deviceClientKey, "Demo Local Device");
        bootstrapRepository.createHmacSecret(clientId, 1, deviceSharedSecret.getBytes(StandardCharsets.UTF_8));
        log.warn("Bootstrapped demo device client '{}'. Rotate shared secret for production.", deviceClientKey);
    }
}
