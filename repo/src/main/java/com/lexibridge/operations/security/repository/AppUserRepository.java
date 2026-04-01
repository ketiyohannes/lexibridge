package com.lexibridge.operations.security.repository;

import com.lexibridge.operations.security.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsernameIgnoreCase(String username);

    @Query(value = """
        select r.code
        from app_role r
        join app_user_role ur on ur.role_id = r.id
        where ur.user_id = :userId
        """, nativeQuery = true)
    List<String> findRoleCodesByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("update AppUser u set u.failedAttempts = :attempts, u.lockoutUntil = :lockoutUntil where u.id = :id")
    void updateFailedAttemptsAndLockout(@Param("id") Long id,
                                        @Param("attempts") int attempts,
                                        @Param("lockoutUntil") LocalDateTime lockoutUntil);

    @Modifying
    @Query("update AppUser u set u.failedAttempts = 0, u.lockoutUntil = null, u.lastLoginAt = :now where u.id = :id")
    void markLoginSuccess(@Param("id") Long id, @Param("now") LocalDateTime now);
}
