package com.lexibridge.operations.modules.leave.model;

import java.time.LocalDate;
import java.util.Map;

public record LeaveRequestCommand(
    long locationId,
    long requesterUserId,
    String leaveType,
    LocalDate startDate,
    LocalDate endDate,
    int durationMinutes,
    Long formVersionId,
    Map<String, Object> formPayload
) {
}
