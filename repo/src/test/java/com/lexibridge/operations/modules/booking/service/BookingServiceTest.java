package com.lexibridge.operations.modules.booking.service;

import com.lexibridge.operations.modules.booking.model.BookingRequest;
import com.lexibridge.operations.modules.booking.repository.BookingRepository;
import com.lexibridge.operations.modules.content.service.MediaValidationService;
import com.lexibridge.operations.governance.AuditLogService;
import com.lexibridge.operations.storage.service.BinaryStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private QrTokenService qrTokenService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private BookingCustomerDataService bookingCustomerDataService;
    @Mock
    private BookingPolicyService bookingPolicyService;
    @Mock
    private MediaValidationService mediaValidationService;
    @Mock
    private BinaryStorageService binaryStorageService;

    private BookingService bookingService;

    @BeforeEach
    void setUp() {
        bookingService = new BookingService(
            bookingRepository,
            qrTokenService,
            auditLogService,
            bookingCustomerDataService,
            bookingPolicyService,
            mediaValidationService,
            binaryStorageService
        );
    }

    @Test
    void reserve_shouldCreateReservationAndToken() {
        BookingRequest request = new BookingRequest(
            1L,
            2L,
            "Alice",
            "555-0100",
            LocalDateTime.of(2026, 1, 10, 10, 0),
            30,
            "first"
        );

        when(bookingRepository.countOccupiedSlotsForUpdate(anyLong(), any(), any())).thenReturn(0);
        when(bookingCustomerDataService.prepareForStorage(any(), any())).thenReturn(
            new BookingCustomerDataService.PreparedCustomer("Alice", "5550100", "enc:alice", "enc:phone")
        );
        when(bookingPolicyService.buildSlotStarts(any(), eq(30))).thenReturn(java.util.List.of(
            LocalDateTime.of(2026, 1, 10, 10, 0),
            LocalDateTime.of(2026, 1, 10, 10, 15)
        ));
        when(bookingRepository.createBookingOrder(anyLong(), any(), any(), any(), any(), any(), any(), eq(2), any(), anyLong(), any(), any())).thenReturn(88L);
        when(bookingRepository.occupySlots(anyLong(), anyList(), anyLong())).thenReturn(2);
        when(qrTokenService.createToken(eq(88L), any())).thenReturn("signed-token");

        Map<String, Object> result = bookingService.reserve(request);

        assertEquals(88L, result.get("bookingId"));
        assertEquals("RESERVED", result.get("status"));
        verify(bookingRepository).occupySlots(eq(1L), anyList(), eq(88L));
    }

    @Test
    void reserve_shouldRejectNon15MinuteDuration() {
        BookingRequest request = new BookingRequest(
            1L,
            2L,
            "Alice",
            null,
            LocalDateTime.now(),
            20,
            null
        );
        doThrow(new IllegalArgumentException("Duration must be a positive multiple of 15 minutes."))
            .when(bookingPolicyService).validateDuration(20);

        assertThrows(IllegalArgumentException.class, () -> bookingService.reserve(request));
    }

    @Test
    void reserve_shouldRejectWhenAtomicSlotUpdateDoesNotCoverAllSlots() {
        BookingRequest request = new BookingRequest(
            1L,
            2L,
            "Alice",
            "555-0100",
            LocalDateTime.of(2026, 1, 10, 10, 0),
            30,
            "first"
        );

        when(bookingRepository.countOccupiedSlotsForUpdate(anyLong(), any(), any())).thenReturn(0);
        when(bookingCustomerDataService.prepareForStorage(any(), any())).thenReturn(
            new BookingCustomerDataService.PreparedCustomer("Alice", "5550100", "enc:alice", "enc:phone")
        );
        when(bookingPolicyService.buildSlotStarts(any(), eq(30))).thenReturn(java.util.List.of(
            LocalDateTime.of(2026, 1, 10, 10, 0),
            LocalDateTime.of(2026, 1, 10, 10, 15)
        ));
        when(bookingRepository.createBookingOrder(anyLong(), any(), any(), any(), any(), any(), any(), anyInt(), any(), anyLong(), any(), any())).thenReturn(88L);
        when(bookingRepository.occupySlots(anyLong(), anyList(), anyLong())).thenReturn(1);

        assertThrows(IllegalStateException.class, () -> bookingService.reserve(request));
    }

    @Test
    void transition_shouldReleaseSlotsOnCancellation() {
        when(bookingRepository.currentState(77L)).thenReturn("RESERVED");
        when(bookingPolicyService.isAllowedTransition("RESERVED", "CANCELLED")).thenReturn(true);

        Map<String, Object> result = bookingService.transition(77L, "cancelled", 9L, "customer request");

        assertEquals("CANCELLED", result.get("to"));
        verify(bookingRepository).freeSlots(77L);
    }

    @Test
    void scanAttendance_shouldPersistTokenHash() {
        when(qrTokenService.validateAndExtractBookingId("abc")).thenReturn(5L);
        when(qrTokenService.hashToken("abc")).thenReturn("hashed");

        bookingService.scanAttendance("abc", 99L);

        verify(bookingRepository).insertAttendanceScan(5L, "hashed", 99L, true);
    }

    @Test
    void reschedule_shouldMoveSlotsAndUpdateWindow() {
        LocalDateTime oldStart = LocalDateTime.of(2026, 1, 10, 10, 0);
        Map<String, Object> booking = new HashMap<>();
        booking.put("id", 77L);
        booking.put("location_id", 1L);
        booking.put("start_at", oldStart);
        booking.put("end_at", oldStart.plusMinutes(30));
        booking.put("status", "RESERVED");
        booking.put("slot_count", 2);

        when(bookingRepository.bookingForUpdate(77L)).thenReturn(booking);
        when(bookingPolicyService.buildSlotStarts(any(), eq(30))).thenReturn(java.util.List.of(
            LocalDateTime.of(2026, 1, 10, 10, 0),
            LocalDateTime.of(2026, 1, 10, 10, 15)
        )).thenReturn(java.util.List.of(
            LocalDateTime.of(2026, 1, 10, 11, 0),
            LocalDateTime.of(2026, 1, 10, 11, 15)
        ));
        when(bookingRepository.countConflictingSlotsForUpdate(anyLong(), any(), any(), anyLong())).thenReturn(0);
        when(bookingRepository.occupySlotsForBooking(anyLong(), anyList(), anyLong())).thenReturn(2);

        Map<String, Object> result = bookingService.reschedule(
            77L,
            LocalDateTime.of(2026, 1, 10, 11, 0),
            30,
            9L,
            "customer request"
        );

        assertEquals(77L, result.get("bookingId"));
        verify(bookingRepository).freeSlots(77L);
        verify(bookingRepository).updateBookingWindow(eq(77L), any(), any(), eq(2), eq("customer request"), any());
    }

    @Test
    void setNoShowAutoCloseOverride_shouldRejectBlankReason() {
        assertThrows(
            IllegalArgumentException.class,
            () -> bookingService.setNoShowAutoCloseOverride(77L, true, " ", 9L)
        );
    }

    @Test
    void printableCard_shouldMaskPiiAndUseLocalQrEndpoint() {
        Map<String, Object> booking = new HashMap<>();
        booking.put("id", 77L);
        booking.put("customer_name", "ENCRYPTED");
        booking.put("customer_phone", "ENCRYPTED");
        booking.put("customer_name_enc", "enc:alice");
        booking.put("customer_phone_enc", "enc:phone");
        booking.put("start_at", LocalDateTime.of(2026, 1, 10, 10, 0));
        booking.put("end_at", LocalDateTime.of(2026, 1, 10, 10, 30));
        booking.put("status", "CONFIRMED");

        when(bookingRepository.bookingById(77L)).thenReturn(booking);
        when(bookingCustomerDataService.maskedNameForDisplay(any())).thenReturn("A***e");
        when(bookingCustomerDataService.maskedPhoneForDisplay(any())).thenReturn("***0100");
        when(qrTokenService.createToken(eq(77L), any())).thenReturn("print-token");

        Map<String, Object> result = bookingService.printableCard(77L, 9L);

        assertEquals(77L, result.get("bookingId"));
        assertEquals("print-token", result.get("qrToken"));
        assertEquals("A***e", result.get("customerName"));
        assertEquals("***0100", result.get("customerPhone"));
        assertTrue(String.valueOf(result.get("qrImageUrl")).startsWith("/portal/bookings/77/qr?token="));
    }

    @Test
    void qrPng_shouldGenerateLocalQrImageBytes() {
        byte[] png = bookingService.qrPng("token-value");

        assertTrue(png.length > 8);
        assertEquals((byte) 0x89, png[0]);
        assertEquals((byte) 0x50, png[1]);
    }
}
