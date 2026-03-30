package com.lexibridge.operations.modules.leave.service;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
public class ApprovalRoutingService {

    public Map<String, Object> pickBestRule(List<Map<String, Object>> rules) {
        if (rules.isEmpty()) {
            throw new IllegalStateException("No approval rule matched request.");
        }

        return rules.stream()
            .min(Comparator
                .comparingInt(this::specificityScore)
                .thenComparingInt(rule -> ((Number) rule.get("priority")).intValue()))
            .orElseThrow();
    }

    private int specificityScore(Map<String, Object> rule) {
        boolean hasApproverUser = rule.get("approver_user_id") != null;
        boolean hasOrg = rule.get("org_unit_id") != null;
        boolean hasLeaveType = rule.get("leave_type") != null;
        boolean hasDuration = rule.get("min_duration_minutes") != null || rule.get("max_duration_minutes") != null;

        if (hasApproverUser) {
            return 0;
        }
        if (hasOrg && hasLeaveType && hasDuration) {
            return 1;
        }
        if (hasOrg) {
            return 2;
        }
        return 3;
    }
}
