package com.lexibridge.operations.modules.booking.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class BookingRepository {

    private final JdbcTemplate jdbcTemplate;

    public BookingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void ensureSlotRows(long locationId, List<LocalDateTime> slotStarts) {
        for (LocalDateTime slotStart : slotStarts) {
            jdbcTemplate.update(
                """
                insert ignore into booking_slot
                (location_id, slot_start_at, slot_end_at, occupancy_state)
                values (?, ?, ?, 'FREE')
                """,
                locationId,
                slotStart,
                slotStart.plusMinutes(15)
            );
        }
    }

    public int countOccupiedSlotsForUpdate(long locationId, LocalDateTime minStart, LocalDateTime maxStart) {
        Integer count = jdbcTemplate.queryForObject(
            """
            select coalesce(sum(case when occupancy_state <> 'FREE' then 1 else 0 end), 0)
            from booking_slot
            where location_id = ?
              and slot_start_at >= ?
              and slot_start_at < ?
            for update
            """,
            Integer.class,
            locationId,
            minStart,
            maxStart
        );
        return count == null ? 0 : count;
    }

    public int countConflictingSlotsForUpdate(long locationId,
                                              LocalDateTime minStart,
                                              LocalDateTime maxStart,
                                              long ignoreBookingOrderId) {
        Integer count = jdbcTemplate.queryForObject(
            """
            select coalesce(sum(case when occupancy_state <> 'FREE' and (booking_order_id is null or booking_order_id <> ?) then 1 else 0 end), 0)
            from booking_slot
            where location_id = ?
              and slot_start_at >= ?
              and slot_start_at < ?
            for update
            """,
            Integer.class,
            ignoreBookingOrderId,
            locationId,
            minStart,
            maxStart
        );
        return count == null ? 0 : count;
    }

    public long createBookingOrder(long locationId,
                                   String customerName,
                                   String customerPhone,
                                   String encryptedCustomerName,
                                   String encryptedCustomerPhone,
                                   LocalDateTime startAt,
                                   LocalDateTime endAt,
                                   int slotCount,
                                   String note,
                                   long createdBy,
                                   LocalDateTime expiresAt,
                                   LocalDateTime noShowCloseAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                """
                insert into booking_order
                (location_id, customer_name, customer_phone, customer_name_enc, customer_phone_enc, start_at, end_at, slot_count, status, order_note, created_by, expires_at, no_show_close_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, 'RESERVED', ?, ?, ?, ?)
                """,
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, locationId);
            ps.setString(2, customerName);
            ps.setString(3, customerPhone);
            ps.setString(4, encryptedCustomerName);
            ps.setString(5, encryptedCustomerPhone);
            ps.setObject(6, startAt);
            ps.setObject(7, endAt);
            ps.setInt(8, slotCount);
            ps.setString(9, note);
            ps.setLong(10, createdBy);
            ps.setObject(11, expiresAt);
            ps.setObject(12, noShowCloseAt);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public int occupySlots(long locationId, List<LocalDateTime> slotStarts, long bookingOrderId) {
        int updated = 0;
        for (LocalDateTime slotStart : slotStarts) {
            updated += jdbcTemplate.update(
                """
                update booking_slot
                set booking_order_id = ?, occupancy_state = 'RESERVED'
                where location_id = ? and slot_start_at = ? and occupancy_state = 'FREE'
                """,
                bookingOrderId,
                locationId,
                slotStart
            );
        }
        return updated;
    }

    public int occupySlotsForBooking(long locationId, List<LocalDateTime> slotStarts, long bookingOrderId) {
        int updated = 0;
        for (LocalDateTime slotStart : slotStarts) {
            updated += jdbcTemplate.update(
                """
                update booking_slot
                set booking_order_id = ?, occupancy_state = 'RESERVED'
                where location_id = ?
                  and slot_start_at = ?
                  and (occupancy_state = 'FREE' or booking_order_id = ?)
                """,
                bookingOrderId,
                locationId,
                slotStart,
                bookingOrderId
            );
        }
        return updated;
    }

    public void transition(long bookingOrderId, String fromState, String toState, String reason, long actorId) {
        jdbcTemplate.update(
            "insert into booking_state_transition (booking_order_id, from_state, to_state, reason_text, changed_by) values (?, ?, ?, ?, ?)",
            bookingOrderId,
            fromState,
            toState,
            reason,
            actorId
        );
    }

    public String currentState(long bookingOrderId) {
        return jdbcTemplate.queryForObject(
            "select status from booking_order where id = ?",
            String.class,
            bookingOrderId
        );
    }

    public void setState(long bookingOrderId, String newState, String overrideReason) {
        jdbcTemplate.update(
            "update booking_order set status = ?, override_reason = ? where id = ?",
            newState,
            overrideReason,
            bookingOrderId
        );
    }

    public void freeSlots(long bookingOrderId) {
        jdbcTemplate.update(
            "update booking_slot set booking_order_id = null, occupancy_state = 'FREE' where booking_order_id = ?",
            bookingOrderId
        );
    }

    public void markSlotsCompleted(long bookingOrderId) {
        jdbcTemplate.update(
            "update booking_slot set occupancy_state = 'COMPLETED' where booking_order_id = ?",
            bookingOrderId
        );
    }

    public void insertAttendanceScan(long bookingOrderId, String tokenHash, long scannedBy, boolean valid) {
        jdbcTemplate.update(
            "insert into attendance_scan (booking_order_id, token_hash, scanned_by, is_valid) values (?, ?, ?, ?)",
            bookingOrderId,
            tokenHash,
            scannedBy,
            valid
        );
    }

    public List<Long> reservationsToExpire() {
        return jdbcTemplate.query(
            "select id from booking_order where status = 'RESERVED' and expires_at <= current_timestamp",
            (rs, rowNum) -> rs.getLong(1)
        );
    }

    public List<Long> confirmedNoShowToClose() {
        return jdbcTemplate.query(
            "select id from booking_order where status = 'CONFIRMED' and no_show_close_at <= current_timestamp and no_show_auto_close_disabled = false",
            (rs, rowNum) -> rs.getLong(1)
        );
    }

    public Map<String, Object> summary(Long locationId) {
        String locationWhere = locationId == null ? "" : " and location_id = ?";
        Integer reserved = jdbcTemplate.queryForObject(
            "select count(*) from booking_order where status = 'RESERVED'" + locationWhere,
            Integer.class,
            locationId == null ? new Object[]{} : new Object[]{locationId}
        );
        Integer confirmed = jdbcTemplate.queryForObject(
            "select count(*) from booking_order where status = 'CONFIRMED'" + locationWhere,
            Integer.class,
            locationId == null ? new Object[]{} : new Object[]{locationId}
        );
        Integer expiringSoon = jdbcTemplate.queryForObject(
            "select count(*) from booking_order where status = 'RESERVED' and expires_at <= date_add(current_timestamp, interval 10 minute)" + locationWhere,
            Integer.class,
            locationId == null ? new Object[]{} : new Object[]{locationId}
        );
        Integer noShowQueue = jdbcTemplate.queryForObject(
            "select count(*) from booking_order where status = 'CONFIRMED' and no_show_close_at <= date_add(current_timestamp, interval 30 minute)" + locationWhere,
            Integer.class,
            locationId == null ? new Object[]{} : new Object[]{locationId}
        );
        return Map.of(
            "reservedNow", reserved == null ? 0 : reserved,
            "confirmedNow", confirmed == null ? 0 : confirmed,
            "expiringIn10Minutes", expiringSoon == null ? 0 : expiringSoon,
            "noShowAutocloseQueue", noShowQueue == null ? 0 : noShowQueue
        );
    }

    public List<Map<String, Object>> latestOrders(Long locationId, int limit) {
        if (locationId == null) {
            return jdbcTemplate.queryForList(
                """
                select id, customer_name, customer_phone, customer_name_enc, customer_phone_enc, start_at, end_at, status,
                       override_reason, no_show_auto_close_disabled, no_show_override_reason, updated_at
                from booking_order
                order by updated_at desc
                limit ?
                """,
                limit
            );
        }
        return jdbcTemplate.queryForList(
            """
            select id, customer_name, customer_phone, customer_name_enc, customer_phone_enc, start_at, end_at, status,
                   override_reason, no_show_auto_close_disabled, no_show_override_reason, updated_at
            from booking_order
            where location_id = ?
            order by updated_at desc
            limit ?
            """,
            locationId,
            limit
        );
    }

    public Map<String, Object> bookingById(long bookingOrderId) {
        return jdbcTemplate.query(
            """
            select id, location_id, customer_name, customer_phone, customer_name_enc, customer_phone_enc,
                   start_at, end_at, status, created_at
            from booking_order
            where id = ?
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                return Map.of(
                    "id", rs.getLong("id"),
                    "location_id", rs.getLong("location_id"),
                    "customer_name", rs.getString("customer_name"),
                    "customer_phone", rs.getString("customer_phone"),
                    "customer_name_enc", rs.getString("customer_name_enc"),
                    "customer_phone_enc", rs.getString("customer_phone_enc"),
                    "start_at", rs.getObject("start_at", LocalDateTime.class),
                    "end_at", rs.getObject("end_at", LocalDateTime.class),
                    "status", rs.getString("status"),
                    "created_at", rs.getObject("created_at", LocalDateTime.class)
                );
            },
            bookingOrderId
        );
    }

    public Map<String, Object> bookingForUpdate(long bookingOrderId) {
        return jdbcTemplate.query(
            """
            select id, location_id, start_at, end_at, status, slot_count
            from booking_order
            where id = ?
            for update
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                return Map.of(
                    "id", rs.getLong("id"),
                    "location_id", rs.getLong("location_id"),
                    "start_at", rs.getObject("start_at", LocalDateTime.class),
                    "end_at", rs.getObject("end_at", LocalDateTime.class),
                    "status", rs.getString("status"),
                    "slot_count", rs.getInt("slot_count")
                );
            },
            bookingOrderId
        );
    }

    public void updateBookingWindow(long bookingOrderId,
                                    LocalDateTime startAt,
                                    LocalDateTime endAt,
                                    int slotCount,
                                    String overrideReason,
                                    LocalDateTime noShowCloseAt) {
        jdbcTemplate.update(
            """
            update booking_order
            set start_at = ?,
                end_at = ?,
                slot_count = ?,
                override_reason = ?,
                no_show_close_at = ?
            where id = ?
            """,
            startAt,
            endAt,
            slotCount,
            overrideReason,
            noShowCloseAt,
            bookingOrderId
        );
    }

    public void setNoShowAutoCloseOverride(long bookingOrderId,
                                           boolean disabled,
                                           String reason,
                                           long actorUserId) {
        jdbcTemplate.update(
            """
            update booking_order
            set no_show_auto_close_disabled = ?,
                no_show_override_reason = ?,
                no_show_overridden_by = ?,
                no_show_overridden_at = current_timestamp
            where id = ?
            """,
            disabled,
            reason,
            actorUserId,
            bookingOrderId
        );
    }

    public int negativeInventorySignals() {
        Integer count = jdbcTemplate.queryForObject(
            """
            select count(*)
            from booking_slot bs
            left join booking_order bo on bo.id = bs.booking_order_id
            where bs.occupancy_state <> 'FREE'
              and bo.id is null
            """,
            Integer.class
        );
        return count == null ? 0 : count;
    }

    public Optional<Long> bookingLocation(long bookingOrderId) {
        Long locationId = jdbcTemplate.query(
            "select location_id from booking_order where id = ?",
            rs -> rs.next() ? (Long) rs.getObject(1) : null,
            bookingOrderId
        );
        return Optional.ofNullable(locationId);
    }

    public long insertAttachment(long bookingOrderId,
                                 String storagePath,
                                 String mimeType,
                                 long fileSizeBytes,
                                 String checksum,
                                 long createdBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                """
                insert into booking_attachment
                (booking_order_id, storage_path, mime_type, file_size_bytes, checksum_sha256, created_by)
                values (?, ?, ?, ?, ?, ?)
                """,
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, bookingOrderId);
            ps.setString(2, storagePath);
            ps.setString(3, mimeType);
            ps.setLong(4, fileSizeBytes);
            ps.setString(5, checksum);
            ps.setLong(6, createdBy);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public List<Map<String, Object>> attachments(long bookingOrderId) {
        return jdbcTemplate.queryForList(
            """
            select id, storage_path, mime_type, file_size_bytes, checksum_sha256, created_by, created_at
            from booking_attachment
            where booking_order_id = ?
            order by id desc
            """,
            bookingOrderId
        );
    }

    public Map<String, Object> attachmentById(long attachmentId) {
        return jdbcTemplate.query(
            """
            select id, booking_order_id, storage_path, mime_type, file_size_bytes, checksum_sha256, created_by, created_at
            from booking_attachment
            where id = ?
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                return Map.of(
                    "id", rs.getLong("id"),
                    "booking_order_id", rs.getLong("booking_order_id"),
                    "storage_path", rs.getString("storage_path"),
                    "mime_type", rs.getString("mime_type"),
                    "file_size_bytes", rs.getLong("file_size_bytes"),
                    "checksum_sha256", rs.getString("checksum_sha256"),
                    "created_by", rs.getLong("created_by"),
                    "created_at", rs.getObject("created_at")
                );
            },
            attachmentId
        );
    }
}
