package com.lexibridge.operations.modules.admin.repository;

import com.lexibridge.operations.security.privacy.FieldEncryptionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

@Repository
public class AdminUserRepository {

    private final JdbcTemplate jdbcTemplate;
    private final FieldEncryptionService fieldEncryptionService;

    public AdminUserRepository(JdbcTemplate jdbcTemplate,
                               FieldEncryptionService fieldEncryptionService) {
        this.jdbcTemplate = jdbcTemplate;
        this.fieldEncryptionService = fieldEncryptionService;
    }

    public List<Map<String, Object>> usersWithRoles(int limit) {
        return jdbcTemplate.queryForList(
            """
            select u.id,
                   u.location_id,
                   l.code as location_code,
                   u.username,
                   u.full_name,
                   u.email,
                   u.is_active,
                   coalesce(group_concat(r.code order by r.code separator ','), '') as role_codes,
                   u.updated_at
            from app_user u
            left join location l on l.id = u.location_id
            left join app_user_role ur on ur.user_id = u.id
            left join app_role r on r.id = ur.role_id
            group by u.id, u.location_id, l.code, u.username, u.full_name, u.email, u.is_active, u.updated_at
            order by u.updated_at desc
            limit ?
            """,
            limit
        );
    }

    public Map<String, Object> userById(long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            """
            select id, location_id, username, full_name, email, is_active
            from app_user
            where id = ?
            """,
            userId
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public long createUser(Long locationId,
                           String username,
                           String fullName,
                           String email,
                           String passwordHash,
                           boolean active) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                """
                insert into app_user (location_id, username, full_name, email, password_hash, is_active)
                values (?, ?, ?, ?, ?, ?)
                """,
                Statement.RETURN_GENERATED_KEYS
            );
            if (locationId == null) {
                ps.setObject(1, null);
            } else {
                ps.setLong(1, locationId);
            }
            ps.setString(2, username);
            ps.setString(3, fullName);
            ps.setString(4, fieldEncryptionService.encryptString(email));
            ps.setString(5, passwordHash);
            ps.setBoolean(6, active);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public int updateUser(long userId,
                          Long locationId,
                          String fullName,
                          String email,
                          boolean active) {
        return jdbcTemplate.update(
            """
            update app_user
            set location_id = ?,
                full_name = ?,
                email = ?,
                is_active = ?
            where id = ?
            """,
            locationId,
            fullName,
            fieldEncryptionService.encryptString(email),
            active,
            userId
        );
    }

    public boolean userExists(long userId) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from app_user where id = ?",
            Integer.class,
            userId
        );
        return count != null && count > 0;
    }

    public Long roleIdByCode(String roleCode) {
        return jdbcTemplate.query(
            "select id from app_role where code = ?",
            rs -> rs.next() ? (Long) rs.getObject(1) : null,
            roleCode
        );
    }

    public void assignRole(long userId, long roleId) {
        jdbcTemplate.update(
            "insert ignore into app_user_role (user_id, role_id) values (?, ?)",
            userId,
            roleId
        );
    }

    public int removeRole(long userId, long roleId) {
        return jdbcTemplate.update(
            "delete from app_user_role where user_id = ? and role_id = ?",
            userId,
            roleId
        );
    }

    public List<String> roleCodesForUser(long userId) {
        return jdbcTemplate.query(
            """
            select r.code
            from app_user_role ur
            join app_role r on r.id = ur.role_id
            where ur.user_id = ?
            order by r.code
            """,
            (rs, rowNum) -> rs.getString(1),
            userId
        );
    }
}
