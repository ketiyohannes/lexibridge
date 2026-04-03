package com.lexibridge.operations.modules.booking.service;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class BookingPolicyService {

    private static final List<String> FINAL_STATES = List.of("CANCELLED", "COMPLETED", "EXPIRED");

    public void validateDuration(int durationMinutes) {
        if (durationMinutes <= 0 || durationMinutes % 15 != 0) {
            throw new IllegalArgumentException("Duration must be a positive multiple of 15 minutes.");
        }
    }

    public boolean isFinalState(String state) {
        return FINAL_STATES.contains(state);
    }

    public boolean isAllowedTransition(String from, String to) {
        return switch (from) {
            case "RESERVED" -> List.of("CONFIRMED", "CANCELLED", "EXPIRED").contains(to);
            case "CONFIRMED" -> List.of("COMPLETED", "CANCELLED").contains(to);
            default -> false;
        };
    }

    public List<LocalDateTime> buildSlotStarts(LocalDateTime startAt, int durationMinutes) {
        int count = durationMinutes / 15;
        List<LocalDateTime> slots = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            slots.add(startAt.plusMinutes((long) i * 15));
        }
        return slots;
    }
}
