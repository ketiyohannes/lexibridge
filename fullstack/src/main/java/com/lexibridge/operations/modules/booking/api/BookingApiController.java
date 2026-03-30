package com.lexibridge.operations.modules.booking.api;

import com.lexibridge.operations.modules.booking.model.BookingRequest;
import com.lexibridge.operations.modules.booking.service.BookingService;
import com.lexibridge.operations.security.service.AuthorizationScopeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/bookings")
@PreAuthorize("hasAnyRole('ADMIN','FRONT_DESK','DEVICE_SERVICE')")
public class BookingApiController {

    private final BookingService bookingService;
    private final AuthorizationScopeService authorizationScopeService;

    public BookingApiController(BookingService bookingService,
                                AuthorizationScopeService authorizationScopeService) {
        this.bookingService = bookingService;
        this.authorizationScopeService = authorizationScopeService;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Long locationId = authorizationScopeService.currentLocationScope().orElse(null);
        return bookingService.dashboardSummary(locationId);
    }

    @PostMapping
    public Map<String, Object> reserve(@Valid @RequestBody ReserveBookingCommand command) {
        authorizationScopeService.assertLocationAccess(command.locationId);
        authorizationScopeService.assertActorUser(command.createdBy);
        return bookingService.reserve(new BookingRequest(
            command.locationId,
            command.createdBy,
            command.customerName,
            command.customerPhone,
            command.startAt,
            command.durationMinutes,
            command.orderNote
        ));
    }

    @PostMapping("/{bookingId}/transition")
    public Map<String, Object> transition(@PathVariable long bookingId,
                                          @Valid @RequestBody TransitionBookingCommand command) {
        authorizationScopeService.assertBookingAccess(bookingId);
        authorizationScopeService.assertActorUser(command.actorUserId);
        return bookingService.transition(bookingId, command.targetState, command.actorUserId, command.reason);
    }

    @PostMapping("/{bookingId}/reschedule")
    public Map<String, Object> reschedule(@PathVariable long bookingId,
                                          @Valid @RequestBody RescheduleBookingCommand command) {
        authorizationScopeService.assertBookingAccess(bookingId);
        authorizationScopeService.assertActorUser(command.actorUserId);
        return bookingService.reschedule(
            bookingId,
            command.startAt,
            command.durationMinutes,
            command.actorUserId,
            command.reason
        );
    }

    @PostMapping("/{bookingId}/no-show-override")
    public Map<String, Object> noShowOverride(@PathVariable long bookingId,
                                              @Valid @RequestBody NoShowOverrideCommand command) {
        authorizationScopeService.assertBookingAccess(bookingId);
        authorizationScopeService.assertActorUser(command.actorUserId);
        return bookingService.setNoShowAutoCloseOverride(
            bookingId,
            command.disableAutoClose,
            command.reason,
            command.actorUserId
        );
    }

    @PostMapping("/attendance/scan")
    public Map<String, Object> scanAttendance(@Valid @RequestBody AttendanceScanCommand command) {
        authorizationScopeService.assertActorUser(command.scannedBy);
        return bookingService.scanAttendance(command.token, command.scannedBy);
    }

    @PostMapping("/{bookingId}/attachments")
    public Map<String, Object> uploadAttachment(@PathVariable long bookingId,
                                                @RequestParam long actorUserId,
                                                @RequestParam("file") MultipartFile file) throws IOException {
        authorizationScopeService.assertBookingAccess(bookingId);
        authorizationScopeService.assertActorUser(actorUserId);
        return bookingService.addAttachment(
            bookingId,
            file.getOriginalFilename() == null ? "attachment.bin" : file.getOriginalFilename(),
            file.getBytes(),
            actorUserId
        );
    }

    @GetMapping("/{bookingId}/attachments")
    public List<Map<String, Object>> attachments(@PathVariable long bookingId) {
        authorizationScopeService.assertBookingAccess(bookingId);
        return bookingService.attachments(bookingId);
    }

    @GetMapping("/{bookingId}/attachments/{attachmentId}/download")
    public ResponseEntity<byte[]> downloadAttachment(@PathVariable long bookingId,
                                                     @PathVariable long attachmentId) {
        authorizationScopeService.assertBookingAccess(bookingId);
        var binary = bookingService.downloadAttachment(bookingId, attachmentId);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(binary.mimeType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=booking-attachment-" + attachmentId)
            .body(binary.payload());
    }

    public static class ReserveBookingCommand {
        @NotNull
        public Long locationId;
        @NotNull
        public Long createdBy;
        @NotBlank
        public String customerName;
        public String customerPhone;
        @NotNull
        public LocalDateTime startAt;
        @Min(15)
        public Integer durationMinutes;
        public String orderNote;
    }

    public static class TransitionBookingCommand {
        @NotBlank
        public String targetState;
        @NotNull
        public Long actorUserId;
        @NotBlank
        public String reason;
    }

    public static class AttendanceScanCommand {
        @NotBlank
        public String token;
        @NotNull
        public Long scannedBy;
    }

    public static class RescheduleBookingCommand {
        @NotNull
        public LocalDateTime startAt;
        @Min(15)
        public Integer durationMinutes;
        @NotNull
        public Long actorUserId;
        @NotBlank
        public String reason;
    }

    public static class NoShowOverrideCommand {
        @NotNull
        public Boolean disableAutoClose;
        @NotBlank
        public String reason;
        @NotNull
        public Long actorUserId;
    }
}
