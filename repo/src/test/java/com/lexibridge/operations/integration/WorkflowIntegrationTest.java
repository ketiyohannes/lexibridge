package com.lexibridge.operations.integration;

import com.lexibridge.operations.modules.booking.model.BookingRequest;
import com.lexibridge.operations.modules.booking.service.BookingService;
import com.lexibridge.operations.modules.payments.service.PaymentsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest(properties = {
    "spring.task.scheduling.enabled=false",
    "lexibridge.security.antivirus.enabled=false"
})
class WorkflowIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("lexibridge")
        .withUsername("lexibridge")
        .withPassword("lexibridge");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired
    private BookingService bookingService;
    @Autowired
    private PaymentsService paymentsService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void bookingConcurrency_shouldAllowOnlyOneReservationForSameSlot() throws Exception {
        long userId = ensureTestUser();
        LocalDateTime start = LocalDateTime.now().plusHours(3).withMinute(0).withSecond(0).withNano(0);
        BookingRequest request = new BookingRequest(
            1L,
            userId,
            "Concurrent Customer",
            "5550001234",
            start,
            30,
            "concurrency-test"
        );

        int attempts = 12;
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        Callable<Boolean> task = () -> {
            try {
                bookingService.reserve(request);
                return true;
            } catch (Exception ex) {
                return false;
            }
        };

        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            futures.add(executor.submit(task));
        }

        int success = 0;
        int failure = 0;
        for (Future<Boolean> future : futures) {
            if (future.get()) {
                success++;
            } else {
                failure++;
            }
        }
        executor.shutdownNow();

        assertEquals(1, success);
        assertEquals(attempts - 1, failure);

        Integer reservedCount = jdbcTemplate.queryForObject(
            "select count(*) from booking_order where order_note = 'concurrency-test' and status = 'RESERVED'",
            Integer.class
        );
        assertEquals(1, reservedCount);
    }

    @Test
    void paymentCallback_shouldBeIdempotentAcrossRetries() {
        long userId = ensureTestUser();
        String marker = "pay-test-" + UUID.randomUUID();
        jdbcTemplate.update(
            """
            insert into booking_order
            (location_id, customer_name, customer_phone, start_at, end_at, slot_count, status, order_note, created_by)
            values (1, ?, '5550009999', current_timestamp, date_add(current_timestamp, interval 30 minute), 2, 'CONFIRMED', ?, ?)
            """,
            marker,
            marker,
            userId
        );

        Long bookingId = jdbcTemplate.queryForObject(
            "select id from booking_order where order_note = ? limit 1",
            Long.class,
            marker
        );

        String terminalId = "T-" + UUID.randomUUID();
        String terminalTxnId = "TXN-" + UUID.randomUUID();
        paymentsService.createTender(
            bookingId,
            "CARD_PRESENT",
            BigDecimal.valueOf(100.00),
            "USD",
            terminalId,
            terminalTxnId,
            userId
        );

        Map<String, Object> first = paymentsService.processCallback(terminalId, terminalTxnId, Map.of("approved", true));
        Map<String, Object> second = paymentsService.processCallback(terminalId, terminalTxnId, Map.of("approved", true));

        assertTrue((Boolean) first.get("firstDelivery"));
        assertFalse((Boolean) second.get("firstDelivery"));

        Integer callbackRows = jdbcTemplate.queryForObject(
            "select count(*) from terminal_callback_log where terminal_id = ? and terminal_txn_id = ?",
            Integer.class,
            terminalId,
            terminalTxnId
        );
        assertEquals(1, callbackRows);

        String tenderStatus = jdbcTemplate.queryForObject(
            "select status from tender_entry where terminal_id = ? and terminal_txn_id = ?",
            String.class,
            terminalId,
            terminalTxnId
        );
        assertEquals("CONFIRMED", tenderStatus);
    }

    @Test
    void paymentCallback_shouldDenyCrossLocationScopedDeviceCallback() {
        ensureLocation(2L);
        long userId = ensureTestUser();
        String marker = "pay-scope-" + UUID.randomUUID();
        jdbcTemplate.update(
            """
            insert into booking_order
            (location_id, customer_name, customer_phone, start_at, end_at, slot_count, status, order_note, created_by)
            values (1, ?, '5550008888', current_timestamp, date_add(current_timestamp, interval 30 minute), 2, 'CONFIRMED', ?, ?)
            """,
            marker,
            marker,
            userId
        );
        Long bookingId = jdbcTemplate.queryForObject("select id from booking_order where order_note = ? limit 1", Long.class, marker);

        String terminalId = "T-" + UUID.randomUUID();
        String terminalTxnId = "TXN-" + UUID.randomUUID();
        paymentsService.createTender(
            bookingId,
            "CARD_PRESENT",
            BigDecimal.valueOf(70.00),
            "USD",
            terminalId,
            terminalTxnId,
            userId
        );

        assertThrows(
            org.springframework.security.access.AccessDeniedException.class,
            () -> paymentsService.processCallback(terminalId, terminalTxnId, Map.of("approved", true), 2L, "device-loc-2")
        );

        String tenderStatus = jdbcTemplate.queryForObject(
            "select status from tender_entry where terminal_id = ? and terminal_txn_id = ?",
            String.class,
            terminalId,
            terminalTxnId
        );
        assertEquals("PENDING", tenderStatus);

        Integer callbackRows = jdbcTemplate.queryForObject(
            "select count(*) from terminal_callback_log where terminal_id = ? and terminal_txn_id = ?",
            Integer.class,
            terminalId,
            terminalTxnId
        );
        assertEquals(0, callbackRows);
    }

    @Test
    void tenderTypeColumn_shouldRejectLegacyCardValueAtDatabaseLevel() {
        long userId = ensureTestUser();
        jdbcTemplate.update(
            """
            insert into booking_order
            (location_id, customer_name, customer_phone, start_at, end_at, slot_count, status, order_note, created_by)
            values (1, 'Enum Test', '5550007777', current_timestamp, date_add(current_timestamp, interval 30 minute), 2, 'CONFIRMED', 'enum-test', ?)
            """,
            userId
        );
        Long bookingId = jdbcTemplate.queryForObject(
            "select id from booking_order where order_note = 'enum-test' order by id desc limit 1",
            Long.class
        );

        assertThrows(
            org.springframework.dao.DataIntegrityViolationException.class,
            () -> jdbcTemplate.update(
                "insert into tender_entry (booking_order_id, tender_type, amount, currency, status, created_by) values (?, 'CARD', 10.00, 'USD', 'PENDING', ?)",
                bookingId,
                userId
            )
        );
    }

    @Test
    void bookingPrintAndScan_shouldSupportReserveToVerifiedFlow() {
        long userId = ensureTestUser();
        LocalDateTime start = LocalDateTime.now().plusHours(4).withMinute(0).withSecond(0).withNano(0);
        Map<String, Object> reserve = bookingService.reserve(new BookingRequest(
            1L,
            userId,
            "Print User",
            "5551234567",
            start,
            30,
            "print-flow"
        ));
        long bookingId = ((Number) reserve.get("bookingId")).longValue();

        Map<String, Object> printable = bookingService.printableCard(bookingId, userId);
        String token = String.valueOf(printable.get("qrToken"));
        assertTrue(token != null && !token.isBlank());

        Map<String, Object> scan = bookingService.scanAttendance(token, userId);
        assertEquals(bookingId, ((Number) scan.get("bookingId")).longValue());

        Integer scans = jdbcTemplate.queryForObject(
            "select count(*) from attendance_scan where booking_order_id = ? and is_valid = true",
            Integer.class,
            bookingId
        );
        assertEquals(1, scans);
    }

    private long ensureTestUser() {
        jdbcTemplate.update(
            """
            insert into app_user (location_id, username, full_name, password_hash, is_active)
            values (1, 'itest-user', 'Integration User', 'x', true)
            on duplicate key update username = username
            """
        );

        Long userId = jdbcTemplate.queryForObject(
            "select id from app_user where username = 'itest-user' limit 1",
            Long.class
        );
        if (userId == null) {
            throw new IllegalStateException("Integration user not found");
        }
        return userId;
    }

    private void ensureLocation(long locationId) {
        Integer count = jdbcTemplate.queryForObject("select count(*) from location where id = ?", Integer.class, locationId);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.update(
            "insert into location (id, code, name, timezone, is_active) values (?, ?, ?, 'UTC', true)",
            locationId,
            "LOC-" + locationId,
            "Location " + locationId
        );
    }
}
