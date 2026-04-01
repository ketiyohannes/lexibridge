package com.lexibridge.operations.modules.booking.model;

import java.time.LocalDateTime;

public record BookingRequest(
    long locationId,
    long createdBy,
    String customerName,
    String customerPhone,
    LocalDateTime startAt,
    int durationMinutes,
    String orderNote
) {
}
