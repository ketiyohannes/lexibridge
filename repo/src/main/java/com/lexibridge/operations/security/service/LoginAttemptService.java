package com.lexibridge.operations.security.service;

import com.lexibridge.operations.security.config.LexiBridgeSecurityProperties;
import com.lexibridge.operations.security.model.AppUser;
import com.lexibridge.operations.security.repository.AppUserRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class LoginAttemptService {

    private final AppUserRepository appUserRepository;
    private final LexiBridgeSecurityProperties securityProperties;

    public LoginAttemptService(AppUserRepository appUserRepository,
                               LexiBridgeSecurityProperties securityProperties) {
        this.appUserRepository = appUserRepository;
        this.securityProperties = securityProperties;
    }

    @Transactional
    public void onFailedLogin(String username) {
        appUserRepository.findByUsernameIgnoreCase(username).ifPresent(user -> {
            int attempts = user.getFailedAttempts() + 1;
            LocalDateTime lockoutUntil = null;
            if (attempts >= securityProperties.getMaxFailedAttempts()) {
                lockoutUntil = LocalDateTime.now().plusMinutes(securityProperties.getLockoutDurationMinutes());
                attempts = securityProperties.getMaxFailedAttempts();
            }
            appUserRepository.updateFailedAttemptsAndLockout(user.getId(), attempts, lockoutUntil);
        });
    }

    @Transactional
    public void onSuccessfulLogin(String username) {
        appUserRepository.findByUsernameIgnoreCase(username)
            .map(AppUser::getId)
            .ifPresent(id -> appUserRepository.markLoginSuccess(id, LocalDateTime.now()));
    }
}
