package com.lexibridge.operations.modules.booking.web;

import com.lexibridge.operations.modules.booking.model.BookingRequest;
import com.lexibridge.operations.modules.booking.service.BookingService;
import com.lexibridge.operations.security.service.AuthorizationScopeService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;

@Controller
public class BookingController {

    private final BookingService bookingService;
    private final AuthorizationScopeService authorizationScopeService;

    public BookingController(BookingService bookingService,
                             AuthorizationScopeService authorizationScopeService) {
        this.bookingService = bookingService;
        this.authorizationScopeService = authorizationScopeService;
    }

    @GetMapping("/portal/bookings")
    public String bookings(Model model) {
        populateBaseModel(model);
        if (!model.containsAttribute("reserveForm")) {
            model.addAttribute("reserveForm", ReserveForm.defaults());
        }
        if (!model.containsAttribute("transitionForm")) {
            model.addAttribute("transitionForm", TransitionForm.defaults());
        }
        if (!model.containsAttribute("scanForm")) {
            model.addAttribute("scanForm", AttendanceScanForm.defaults());
        }
        if (!model.containsAttribute("rescheduleForm")) {
            model.addAttribute("rescheduleForm", RescheduleForm.defaults());
        }
        if (!model.containsAttribute("noShowOverrideForm")) {
            model.addAttribute("noShowOverrideForm", NoShowOverrideForm.defaults());
        }
        return "portal/bookings";
    }

    @PostMapping("/portal/bookings/reserve")
    public String reserve(@ModelAttribute ReserveForm reserveForm, Model model) {
        populateBaseModel(model);
        model.addAttribute("reserveForm", reserveForm);
        model.addAttribute("transitionForm", TransitionForm.defaults());
        model.addAttribute("scanForm", AttendanceScanForm.defaults());
        model.addAttribute("rescheduleForm", RescheduleForm.defaults());
        model.addAttribute("noShowOverrideForm", NoShowOverrideForm.defaults());

        String validationError = validateReserveForm(reserveForm);
        if (validationError != null) {
            model.addAttribute("bookingError", validationError);
            return "portal/bookings";
        }

        authorizationScopeService.assertLocationAccess(reserveForm.getLocationId());
        try {
            Map<String, Object> result = bookingService.reserve(new BookingRequest(
                reserveForm.getLocationId(),
                currentUserId(),
                reserveForm.getCustomerName(),
                reserveForm.getCustomerPhone(),
                reserveForm.getStartAt(),
                reserveForm.getDurationMinutes(),
                reserveForm.getOrderNote()
            ));
            model.addAttribute("reserveResult", result);
            model.addAttribute("bookingSuccess", "Booking created successfully.");
            model.addAttribute("scanForm", AttendanceScanForm.fromToken((String) result.get("qrToken")));
        } catch (RuntimeException ex) {
            model.addAttribute("bookingError", ex.getMessage());
        }
        return "portal/bookings";
    }

    @PostMapping("/portal/bookings/{bookingId}/transition")
    public String transition(@PathVariable long bookingId,
                             @ModelAttribute TransitionForm transitionForm,
                             Model model) {
        authorizationScopeService.assertBookingAccess(bookingId);
        populateBaseModel(model);
        model.addAttribute("reserveForm", ReserveForm.defaults());
        model.addAttribute("transitionForm", transitionForm);
        model.addAttribute("scanForm", AttendanceScanForm.defaults());
        model.addAttribute("rescheduleForm", RescheduleForm.defaults());
        model.addAttribute("noShowOverrideForm", NoShowOverrideForm.defaults());

        if (transitionForm.getTargetState() == null || transitionForm.getTargetState().isBlank()) {
            model.addAttribute("bookingError", "Target state is required.");
            return "portal/bookings";
        }

        try {
            Map<String, Object> result = bookingService.transition(
                bookingId,
                transitionForm.getTargetState(),
                currentUserId(),
                transitionForm.getReason()
            );
            model.addAttribute("transitionResult", result);
            model.addAttribute("bookingSuccess", "Booking #" + bookingId + " transitioned.");
        } catch (RuntimeException ex) {
            model.addAttribute("bookingError", ex.getMessage());
        }
        return "portal/bookings";
    }

    @PostMapping("/portal/bookings/attendance/scan")
    public String scanAttendance(@ModelAttribute AttendanceScanForm scanForm, Model model) {
        populateBaseModel(model);
        model.addAttribute("reserveForm", ReserveForm.defaults());
        model.addAttribute("transitionForm", TransitionForm.defaults());
        model.addAttribute("scanForm", scanForm);
        model.addAttribute("rescheduleForm", RescheduleForm.defaults());
        model.addAttribute("noShowOverrideForm", NoShowOverrideForm.defaults());

        if (scanForm.getToken() == null || scanForm.getToken().isBlank()) {
            model.addAttribute("bookingError", "QR token is required for attendance scan.");
            return "portal/bookings";
        }
        try {
            Map<String, Object> result = bookingService.scanAttendance(scanForm.getToken(), currentUserId());
            model.addAttribute("scanResult", result);
            model.addAttribute("bookingSuccess", "Attendance scan verified.");
        } catch (RuntimeException ex) {
            model.addAttribute("bookingError", ex.getMessage());
        }
        return "portal/bookings";
    }

    @PostMapping("/portal/bookings/{bookingId}/attachments")
    public String uploadAttachment(@PathVariable long bookingId,
                                   @RequestParam("file") MultipartFile file,
                                   Model model) throws IOException {
        authorizationScopeService.assertBookingAccess(bookingId);
        populateBaseModel(model);
        model.addAttribute("reserveForm", ReserveForm.defaults());
        model.addAttribute("transitionForm", TransitionForm.defaults());
        model.addAttribute("scanForm", AttendanceScanForm.defaults());
        model.addAttribute("rescheduleForm", RescheduleForm.defaults());
        model.addAttribute("noShowOverrideForm", NoShowOverrideForm.defaults());

        try {
            Map<String, Object> result = bookingService.addAttachment(
                bookingId,
                file.getOriginalFilename() == null ? "attachment.bin" : file.getOriginalFilename(),
                file.getBytes(),
                currentUserId()
            );
            model.addAttribute("bookingSuccess", "Attachment uploaded for booking #" + bookingId + ".");
            model.addAttribute("attachmentResult", result);
            model.addAttribute("selectedBookingAttachments", bookingService.attachments(bookingId));
            model.addAttribute("selectedBookingId", bookingId);
        } catch (RuntimeException ex) {
            model.addAttribute("bookingError", ex.getMessage());
        }
        return "portal/bookings";
    }

    @PostMapping("/portal/bookings/{bookingId}/reschedule")
    public String reschedule(@PathVariable long bookingId,
                             @ModelAttribute RescheduleForm rescheduleForm,
                             Model model) {
        authorizationScopeService.assertBookingAccess(bookingId);
        populateBaseModel(model);
        model.addAttribute("reserveForm", ReserveForm.defaults());
        model.addAttribute("transitionForm", TransitionForm.defaults());
        model.addAttribute("scanForm", AttendanceScanForm.defaults());
        model.addAttribute("rescheduleForm", rescheduleForm);
        model.addAttribute("noShowOverrideForm", NoShowOverrideForm.defaults());

        if (rescheduleForm.getStartAt() == null) {
            model.addAttribute("bookingError", "New start date/time is required.");
            return "portal/bookings";
        }
        if (rescheduleForm.getDurationMinutes() == null || rescheduleForm.getDurationMinutes() <= 0) {
            model.addAttribute("bookingError", "Duration minutes must be positive.");
            return "portal/bookings";
        }
        if (rescheduleForm.getReason() == null || rescheduleForm.getReason().isBlank()) {
            model.addAttribute("bookingError", "Reschedule reason is required.");
            return "portal/bookings";
        }

        try {
            Map<String, Object> result = bookingService.reschedule(
                bookingId,
                rescheduleForm.getStartAt(),
                rescheduleForm.getDurationMinutes(),
                currentUserId(),
                rescheduleForm.getReason()
            );
            model.addAttribute("rescheduleResult", result);
            model.addAttribute("bookingSuccess", "Booking #" + bookingId + " rescheduled.");
        } catch (RuntimeException ex) {
            model.addAttribute("bookingError", ex.getMessage());
        }
        return "portal/bookings";
    }

    @PostMapping("/portal/bookings/{bookingId}/no-show-override")
    public String noShowOverride(@PathVariable long bookingId,
                                 @ModelAttribute NoShowOverrideForm noShowOverrideForm,
                                 Model model) {
        authorizationScopeService.assertBookingAccess(bookingId);
        populateBaseModel(model);
        model.addAttribute("reserveForm", ReserveForm.defaults());
        model.addAttribute("transitionForm", TransitionForm.defaults());
        model.addAttribute("scanForm", AttendanceScanForm.defaults());
        model.addAttribute("rescheduleForm", RescheduleForm.defaults());
        model.addAttribute("noShowOverrideForm", noShowOverrideForm);

        if (noShowOverrideForm.getReason() == null || noShowOverrideForm.getReason().isBlank()) {
            model.addAttribute("bookingError", "Override reason is required.");
            return "portal/bookings";
        }

        try {
            Map<String, Object> result = bookingService.setNoShowAutoCloseOverride(
                bookingId,
                noShowOverrideForm.getDisableAutoClose(),
                noShowOverrideForm.getReason(),
                currentUserId()
            );
            model.addAttribute("noShowOverrideResult", result);
            model.addAttribute("bookingSuccess", "No-show auto-close override updated for booking #" + bookingId + ".");
        } catch (RuntimeException ex) {
            model.addAttribute("bookingError", ex.getMessage());
        }
        return "portal/bookings";
    }

    @GetMapping("/portal/bookings/{bookingId}/print")
    public String printCard(@PathVariable long bookingId, Model model) {
        authorizationScopeService.assertBookingAccess(bookingId);
        model.addAttribute("printCard", bookingService.printableCard(bookingId, currentUserId()));
        return "portal/booking-print";
    }

    @GetMapping(value = "/portal/bookings/{bookingId}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    @ResponseBody
    public byte[] printQr(@PathVariable long bookingId,
                          @RequestParam String token) {
        authorizationScopeService.assertBookingAccess(bookingId);
        return bookingService.qrPng(token);
    }

    private String validateReserveForm(ReserveForm form) {
        if (form.getLocationId() == null || form.getLocationId() <= 0) {
            return "Location ID must be a positive number.";
        }
        if (form.getCustomerName() == null || form.getCustomerName().isBlank()) {
            return "Customer name is required.";
        }
        if (form.getStartAt() == null) {
            return "Start date/time is required.";
        }
        if (form.getDurationMinutes() == null || form.getDurationMinutes() <= 0) {
            return "Duration must be a positive number of minutes.";
        }
        return null;
    }

    private void populateBaseModel(Model model) {
        Long locationId = authorizationScopeService.currentLocationScope().orElse(null);
        model.addAttribute("summary", bookingService.dashboardSummary(locationId));
        model.addAttribute("timeline", bookingService.latestTimeline(locationId));
    }

    private long currentUserId() {
        return authorizationScopeService.requireCurrentUserId();
    }

    public static final class ReserveForm {
        private Long locationId;
        private Long createdBy;
        private String customerName;
        private String customerPhone;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime startAt;
        private Integer durationMinutes;
        private String orderNote;

        public static ReserveForm defaults() {
            ReserveForm form = new ReserveForm();
            form.setLocationId(1L);
            form.setCreatedBy(1L);
            form.setStartAt(LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0));
            form.setDurationMinutes(60);
            return form;
        }

        public Long getLocationId() { return locationId; }
        public void setLocationId(Long locationId) { this.locationId = locationId; }
        public Long getCreatedBy() { return createdBy; }
        public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
        public String getCustomerName() { return customerName; }
        public void setCustomerName(String customerName) { this.customerName = customerName; }
        public String getCustomerPhone() { return customerPhone; }
        public void setCustomerPhone(String customerPhone) { this.customerPhone = customerPhone; }
        public LocalDateTime getStartAt() { return startAt; }
        public void setStartAt(LocalDateTime startAt) { this.startAt = startAt; }
        public Integer getDurationMinutes() { return durationMinutes; }
        public void setDurationMinutes(Integer durationMinutes) { this.durationMinutes = durationMinutes; }
        public String getOrderNote() { return orderNote; }
        public void setOrderNote(String orderNote) { this.orderNote = orderNote; }
    }

    public static final class TransitionForm {
        private String targetState;
        private Long actorUserId;
        private String reason;

        public static TransitionForm defaults() {
            TransitionForm form = new TransitionForm();
            form.setTargetState("CONFIRMED");
            form.setActorUserId(1L);
            form.setReason("Operator action");
            return form;
        }

        public String getTargetState() { return targetState; }
        public void setTargetState(String targetState) { this.targetState = targetState; }
        public Long getActorUserId() { return actorUserId; }
        public void setActorUserId(Long actorUserId) { this.actorUserId = actorUserId; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    public static final class AttendanceScanForm {
        private String token;
        private Long scannedBy;

        public static AttendanceScanForm defaults() {
            AttendanceScanForm form = new AttendanceScanForm();
            form.setScannedBy(1L);
            return form;
        }

        public static AttendanceScanForm fromToken(String token) {
            AttendanceScanForm form = defaults();
            form.setToken(token);
            return form;
        }

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public Long getScannedBy() { return scannedBy; }
        public void setScannedBy(Long scannedBy) { this.scannedBy = scannedBy; }
    }

    public static final class RescheduleForm {
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime startAt;
        private Integer durationMinutes;
        private String reason;

        public static RescheduleForm defaults() {
            RescheduleForm form = new RescheduleForm();
            form.setStartAt(LocalDateTime.now().plusHours(2).withMinute(0).withSecond(0).withNano(0));
            form.setDurationMinutes(60);
            form.setReason("Customer requested time change");
            return form;
        }

        public LocalDateTime getStartAt() { return startAt; }
        public void setStartAt(LocalDateTime startAt) { this.startAt = startAt; }
        public Integer getDurationMinutes() { return durationMinutes; }
        public void setDurationMinutes(Integer durationMinutes) { this.durationMinutes = durationMinutes; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    public static final class NoShowOverrideForm {
        private Boolean disableAutoClose;
        private String reason;

        public static NoShowOverrideForm defaults() {
            NoShowOverrideForm form = new NoShowOverrideForm();
            form.setDisableAutoClose(true);
            form.setReason("Manual follow-up in progress");
            return form;
        }

        public Boolean getDisableAutoClose() { return disableAutoClose != null && disableAutoClose; }
        public void setDisableAutoClose(Boolean disableAutoClose) { this.disableAutoClose = disableAutoClose; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
}
