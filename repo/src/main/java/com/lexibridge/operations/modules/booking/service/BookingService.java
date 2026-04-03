package com.lexibridge.operations.modules.booking.service;

import com.lexibridge.operations.governance.AuditLogService;
import com.lexibridge.operations.modules.booking.model.BookingRequest;
import com.lexibridge.operations.modules.content.model.FileValidationResult;
import com.lexibridge.operations.modules.content.service.MediaValidationService;
import com.lexibridge.operations.modules.booking.repository.BookingRepository;
import com.lexibridge.operations.storage.service.BinaryStorageService;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

@Service
public class BookingService {

    private static final int QR_SIZE_PX = 220;

    private final BookingRepository bookingRepository;
    private final QrTokenService qrTokenService;
    private final AuditLogService auditLogService;
    private final BookingCustomerDataService bookingCustomerDataService;
    private final BookingPolicyService bookingPolicyService;
    private final MediaValidationService mediaValidationService;
    private final BinaryStorageService binaryStorageService;

    public BookingService(BookingRepository bookingRepository,
                          QrTokenService qrTokenService,
                          AuditLogService auditLogService,
                          BookingCustomerDataService bookingCustomerDataService,
                          BookingPolicyService bookingPolicyService,
                          MediaValidationService mediaValidationService,
                          BinaryStorageService binaryStorageService) {
        this.bookingRepository = bookingRepository;
        this.qrTokenService = qrTokenService;
        this.auditLogService = auditLogService;
        this.bookingCustomerDataService = bookingCustomerDataService;
        this.bookingPolicyService = bookingPolicyService;
        this.mediaValidationService = mediaValidationService;
        this.binaryStorageService = binaryStorageService;
    }

    public Map<String, Object> dashboardSummary() {
        return bookingRepository.summary(null);
    }

    public Map<String, Object> dashboardSummary(Long locationId) {
        return bookingRepository.summary(locationId);
    }

    @Transactional
    public Map<String, Object> reserve(BookingRequest request) {
        bookingPolicyService.validateDuration(request.durationMinutes());

        List<LocalDateTime> slotStarts = bookingPolicyService.buildSlotStarts(request.startAt(), request.durationMinutes());
        bookingRepository.ensureSlotRows(request.locationId(), slotStarts);

        int occupied = bookingRepository.countOccupiedSlotsForUpdate(
            request.locationId(),
            slotStarts.getFirst(),
            slotStarts.getLast().plusMinutes(15)
        );
        if (occupied > 0) {
            throw new IllegalStateException("One or more slots are already occupied.");
        }

        LocalDateTime endAt = request.startAt().plusMinutes(request.durationMinutes());
        BookingCustomerDataService.PreparedCustomer customer = bookingCustomerDataService.prepareForStorage(request.customerName(), request.customerPhone());

        long bookingId = bookingRepository.createBookingOrder(
            request.locationId(),
            "ENCRYPTED",
            customer.normalizedPhone() == null || customer.normalizedPhone().isBlank() ? null : "ENCRYPTED",
            customer.encryptedName(),
            customer.encryptedPhone(),
            request.startAt(),
            endAt,
            slotStarts.size(),
            request.orderNote(),
            request.createdBy(),
            LocalDateTime.now().plusMinutes(10),
            endAt.plusMinutes(30)
        );

        int occupiedRows = bookingRepository.occupySlots(request.locationId(), slotStarts, bookingId);
        if (occupiedRows != slotStarts.size()) {
            throw new IllegalStateException("One or more slots are already occupied.");
        }
        bookingRepository.transition(bookingId, "NONE", "RESERVED", "Booking created", request.createdBy());
        auditLogService.logUserEvent(request.createdBy(), "BOOKING_CREATED", "booking_order", String.valueOf(bookingId), request.locationId(), Map.of("slotCount", slotStarts.size()));

        String qrToken = qrTokenService.createToken(bookingId, endAt.plusDays(1));
        return Map.of("bookingId", bookingId, "status", "RESERVED", "qrToken", qrToken);
    }

    @Transactional
    public Map<String, Object> transition(long bookingOrderId, String targetState, long actorId, String reason) {
        String fromState = bookingRepository.currentState(bookingOrderId);
        String toState = targetState.trim().toUpperCase();

        if (bookingPolicyService.isFinalState(fromState)) {
            throw new IllegalStateException("Cannot transition from final state: " + fromState);
        }

        if (!bookingPolicyService.isAllowedTransition(fromState, toState)) {
            throw new IllegalStateException("Invalid transition: " + fromState + " -> " + toState);
        }

        bookingRepository.setState(bookingOrderId, toState, reason);
        bookingRepository.transition(bookingOrderId, fromState, toState, reason, actorId);

        if ("CANCELLED".equals(toState) || "EXPIRED".equals(toState)) {
            bookingRepository.freeSlots(bookingOrderId);
        } else if ("COMPLETED".equals(toState)) {
            bookingRepository.markSlotsCompleted(bookingOrderId);
        }

        auditLogService.logUserEvent(actorId, "BOOKING_STATE_CHANGED", "booking_order", String.valueOf(bookingOrderId), null, Map.of("from", fromState, "to", toState));
        return Map.of("bookingId", bookingOrderId, "from", fromState, "to", toState);
    }

    @Transactional
    public Map<String, Object> scanAttendance(String token, long scannedBy) {
        long bookingId = decodeAttendanceToken(token);
        return scanAttendanceForBooking(token, bookingId, scannedBy);
    }

    public long decodeAttendanceToken(String token) {
        return qrTokenService.validateAndExtractBookingId(token);
    }

    @Transactional
    public Map<String, Object> scanAttendanceForBooking(String token, long bookingId, long scannedBy) {
        bookingRepository.insertAttendanceScan(bookingId, qrTokenService.hashToken(token), scannedBy, true);
        auditLogService.logUserEvent(scannedBy, "ATTENDANCE_VERIFIED", "booking_order", String.valueOf(bookingId), null, Map.of());
        return Map.of("bookingId", bookingId, "attendance", "VERIFIED");
    }

    @Transactional
    public int expireUnconfirmedReservations() {
        List<Long> ids = bookingRepository.reservationsToExpire();
        for (Long id : ids) {
            transition(id, "EXPIRED", 1L, "Auto-expired after 10 minutes");
        }
        return ids.size();
    }

    @Transactional
    public int autoCloseNoShows() {
        List<Long> ids = bookingRepository.confirmedNoShowToClose();
        for (Long id : ids) {
            transition(id, "COMPLETED", 1L, "Auto-closed after no-show grace window");
        }
        return ids.size();
    }

    public List<Map<String, Object>> latestTimeline() {
        return latestTimeline(null);
    }

    public List<Map<String, Object>> latestTimeline(Long locationId) {
        return bookingRepository.latestOrders(locationId, 20)
            .stream()
            .map(row -> {
                Map<String, Object> timelineRow = new LinkedHashMap<>();
                timelineRow.put("id", row.get("id"));
                timelineRow.put("customerName", bookingCustomerDataService.maskedNameForDisplay(row));
                timelineRow.put("customerPhone", bookingCustomerDataService.maskedPhoneForDisplay(row));
                timelineRow.put("startAt", row.get("start_at"));
                timelineRow.put("endAt", row.get("end_at"));
                timelineRow.put("status", row.get("status"));
                timelineRow.put("overrideReason", row.get("override_reason"));
                timelineRow.put("noShowAutoCloseDisabled", row.get("no_show_auto_close_disabled"));
                timelineRow.put("noShowOverrideReason", row.get("no_show_override_reason"));
                timelineRow.put("updatedAt", row.get("updated_at"));
                return timelineRow;
            })
            .toList();
    }

    @Transactional
    public Map<String, Object> reschedule(long bookingOrderId,
                                          LocalDateTime newStartAt,
                                          int durationMinutes,
                                          long actorUserId,
                                          String reason) {
        bookingPolicyService.validateDuration(durationMinutes);
        Map<String, Object> current = bookingRepository.bookingForUpdate(bookingOrderId);
        if (current == null) {
            throw new IllegalArgumentException("Booking order not found.");
        }

        String currentStatus = (String) current.get("status");
        if (!List.of("RESERVED", "CONFIRMED").contains(currentStatus)) {
            throw new IllegalStateException("Booking can only be rescheduled from RESERVED or CONFIRMED state.");
        }

        long locationId = ((Number) current.get("location_id")).longValue();
        List<LocalDateTime> oldSlots = bookingPolicyService.buildSlotStarts(
            (LocalDateTime) current.get("start_at"),
            ((Number) current.get("slot_count")).intValue() * 15
        );
        List<LocalDateTime> newSlots = bookingPolicyService.buildSlotStarts(newStartAt, durationMinutes);

        bookingRepository.ensureSlotRows(locationId, newSlots);
        int conflicts = bookingRepository.countConflictingSlotsForUpdate(
            locationId,
            newSlots.getFirst(),
            newSlots.getLast().plusMinutes(15),
            bookingOrderId
        );
        if (conflicts > 0) {
            throw new IllegalStateException("One or more target slots are already occupied.");
        }

        bookingRepository.freeSlots(bookingOrderId);
        int occupiedRows = bookingRepository.occupySlotsForBooking(locationId, newSlots, bookingOrderId);
        if (occupiedRows != newSlots.size()) {
            throw new IllegalStateException("Failed to reserve all target slots.");
        }

        LocalDateTime newEndAt = newStartAt.plusMinutes(durationMinutes);
        bookingRepository.updateBookingWindow(
            bookingOrderId,
            newStartAt,
            newEndAt,
            newSlots.size(),
            reason,
            newEndAt.plusMinutes(30)
        );
        bookingRepository.transition(
            bookingOrderId,
            currentStatus,
            currentStatus,
            "Rescheduled from " + oldSlots.getFirst() + " to " + newStartAt + ". " + reason,
            actorUserId
        );
        auditLogService.logUserEvent(
            actorUserId,
            "BOOKING_RESCHEDULED",
            "booking_order",
            String.valueOf(bookingOrderId),
            locationId,
            Map.of("newStartAt", newStartAt, "newEndAt", newEndAt, "durationMinutes", durationMinutes)
        );
        return Map.of(
            "bookingId", bookingOrderId,
            "status", currentStatus,
            "startAt", newStartAt,
            "endAt", newEndAt,
            "slotCount", newSlots.size()
        );
    }

    @Transactional
    public Map<String, Object> setNoShowAutoCloseOverride(long bookingOrderId,
                                                           boolean disabled,
                                                           String reason,
                                                           long actorUserId) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Override reason is required.");
        }
        Map<String, Object> booking = bookingRepository.bookingForUpdate(bookingOrderId);
        if (booking == null) {
            throw new IllegalArgumentException("Booking order not found.");
        }
        bookingRepository.setNoShowAutoCloseOverride(bookingOrderId, disabled, reason, actorUserId);
        auditLogService.logUserEvent(
            actorUserId,
            "BOOKING_NO_SHOW_OVERRIDE_UPDATED",
            "booking_order",
            String.valueOf(bookingOrderId),
            null,
            Map.of("disabled", disabled, "reason", reason)
        );
        return Map.of("bookingId", bookingOrderId, "noShowAutoCloseDisabled", disabled, "reason", reason);
    }

    public int negativeInventorySignals() {
        return bookingRepository.negativeInventorySignals();
    }

    @Transactional
    public Map<String, Object> addAttachment(long bookingOrderId,
                                             String filename,
                                             byte[] bytes,
                                             long actorUserId) {
        FileValidationResult validation = mediaValidationService.validate(filename, bytes);
        if (!validation.valid()) {
            throw new IllegalArgumentException("Attachment rejected: " + validation.reason());
        }

        bookingRepository.bookingLocation(bookingOrderId)
            .orElseThrow(() -> new IllegalArgumentException("Booking order not found."));

        String storagePath = "booking-attachments/" + bookingOrderId + "/" + validation.checksumSha256();
        binaryStorageService.store(storagePath, validation.checksumSha256(), validation.detectedMime(), bytes);

        long attachmentId = bookingRepository.insertAttachment(
            bookingOrderId,
            storagePath,
            validation.detectedMime(),
            bytes.length,
            validation.checksumSha256(),
            actorUserId
        );
        auditLogService.logUserEvent(actorUserId, "BOOKING_ATTACHMENT_ADDED", "booking_attachment", String.valueOf(attachmentId), null, Map.of("bookingId", bookingOrderId));
        return Map.of("attachmentId", attachmentId, "bookingId", bookingOrderId, "status", "UPLOADED");
    }

    public List<Map<String, Object>> attachments(long bookingOrderId) {
        return bookingRepository.attachments(bookingOrderId);
    }

    public BinaryStorageService.DownloadedBinary downloadAttachment(long bookingOrderId, long attachmentId) {
        Map<String, Object> attachment = bookingRepository.attachmentById(attachmentId);
        if (attachment == null) {
            throw new IllegalArgumentException("Booking attachment not found.");
        }
        long actualBookingId = ((Number) attachment.get("booking_order_id")).longValue();
        if (actualBookingId != bookingOrderId) {
            throw new IllegalArgumentException("Booking attachment does not belong to requested booking.");
        }
        return binaryStorageService.read(String.valueOf(attachment.get("storage_path")));
    }

    public Map<String, Object> printableCard(long bookingOrderId, long actorUserId) {
        Map<String, Object> booking = bookingRepository.bookingById(bookingOrderId);
        if (booking == null) {
            throw new IllegalArgumentException("Booking order not found.");
        }
        LocalDateTime endAt = (LocalDateTime) booking.get("end_at");
        String token = qrTokenService.createToken(bookingOrderId, endAt.plusDays(1));
        String encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8);
        auditLogService.logUserEvent(actorUserId, "BOOKING_PRINT_CARD_VIEWED", "booking_order", String.valueOf(bookingOrderId), null, Map.of());
        return Map.of(
            "bookingId", bookingOrderId,
            "customerName", bookingCustomerDataService.maskedNameForDisplay(booking),
            "customerPhone", bookingCustomerDataService.maskedPhoneForDisplay(booking),
            "startAt", booking.get("start_at"),
            "endAt", endAt,
            "status", booking.get("status"),
            "qrToken", token,
            "qrImageUrl", "/portal/bookings/" + bookingOrderId + "/qr?token=" + encodedToken
        );
    }

    public byte[] qrPng(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("QR token is required.");
        }
        try {
            BitMatrix bitMatrix = new QRCodeWriter().encode(token, BarcodeFormat.QR_CODE, QR_SIZE_PX, QR_SIZE_PX);
            BufferedImage image = MatrixToImageWriter.toBufferedImage(bitMatrix);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", output);
            return output.toByteArray();
        } catch (WriterException | IOException ex) {
            throw new IllegalStateException("Unable to generate QR image.", ex);
        }
    }
}
