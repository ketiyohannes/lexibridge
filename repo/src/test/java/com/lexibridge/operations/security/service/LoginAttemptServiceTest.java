package com.lexibridge.operations.security.service;

import com.lexibridge.operations.security.config.LexiBridgeSecurityProperties;
import com.lexibridge.operations.security.model.AppUser;
import com.lexibridge.operations.security.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginAttemptServiceTest {

    @Mock
    private AppUserRepository appUserRepository;

    private LoginAttemptService loginAttemptService;

    @BeforeEach
    void setUp() {
        LexiBridgeSecurityProperties properties = new LexiBridgeSecurityProperties();
        properties.setMaxFailedAttempts(5);
        properties.setLockoutDurationMinutes(15);
        loginAttemptService = new LoginAttemptService(appUserRepository, properties);
    }

    @Test
    void onFailedLogin_shouldIncrementAttemptsAndLockAtThreshold() {
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(42L);
        when(user.getFailedAttempts()).thenReturn(4);
        when(appUserRepository.findByUsernameIgnoreCase("admin")).thenReturn(Optional.of(user));

        loginAttemptService.onFailedLogin("admin");

        verify(appUserRepository).updateFailedAttemptsAndLockout(eq(42L), eq(5), any());
    }

    @Test
    void onSuccessfulLogin_shouldResetAttempts() {
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(42L);
        when(appUserRepository.findByUsernameIgnoreCase("admin")).thenReturn(Optional.of(user));

        loginAttemptService.onSuccessfulLogin("admin");

        verify(appUserRepository).markLoginSuccess(eq(42L), any());
    }
}
