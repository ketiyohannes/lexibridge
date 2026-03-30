package com.lexibridge.operations.security.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;

@Component
public class ProductionSafetyGuard implements ApplicationRunner {

    private static final Set<String> SAFE_PROFILES = Set.of("local", "dev", "test", "default");

    private final Environment environment;

    @Value("${lexibridge.bootstrap.admin.password:ChangeMe123!@}")
    private String adminPassword;

    @Value("${lexibridge.bootstrap.device.shared-secret:demo-device-shared-secret}")
    private String deviceSharedSecret;

    @Value("${lexibridge.security.field-encryption-key:0123456789abcdef0123456789abcdef}")
    private String fieldEncryptionKey;

    public ProductionSafetyGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length == 0) {
            profiles = environment.getDefaultProfiles();
        }

        boolean safeProfile = Arrays.stream(profiles).anyMatch(SAFE_PROFILES::contains);
        if (safeProfile) {
            return;
        }

        if ("ChangeMe123!@".equals(adminPassword)
            || "demo-device-shared-secret".equals(deviceSharedSecret)
            || "0123456789abcdef0123456789abcdef".equals(fieldEncryptionKey)) {
            throw new IllegalStateException("Refusing to start with insecure default security secrets outside local/dev/test profiles.");
        }
    }
}
