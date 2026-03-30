package com.lexibridge.operations.modules.leave.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApprovalRoutingServiceTest {

    private final ApprovalRoutingService routingService = new ApprovalRoutingService();

    @Test
    void pickBestRule_shouldPreferExplicitUserRule() {
        Map<String, Object> explicitUserRule = Map.of(
            "priority", 10,
            "approver_user_id", 77L,
            "approver_role_code", "MANAGER"
        );
        Map<String, Object> orgRule = Map.of(
            "priority", 1,
            "org_unit_id", 2L,
            "leave_type", "ANNUAL",
            "min_duration_minutes", 60,
            "approver_role_code", "HR_APPROVER"
        );

        Map<String, Object> selected = routingService.pickBestRule(List.of(orgRule, explicitUserRule));
        assertEquals(77L, selected.get("approver_user_id"));
    }

    @Test
    void pickBestRule_shouldUsePriorityWhenSpecificityTie() {
        Map<String, Object> first = Map.of(
            "priority", 2,
            "org_unit_id", 2L,
            "approver_role_code", "MANAGER"
        );
        Map<String, Object> second = Map.of(
            "priority", 1,
            "org_unit_id", 2L,
            "approver_role_code", "HR_APPROVER"
        );

        Map<String, Object> selected = routingService.pickBestRule(List.of(first, second));
        assertEquals("HR_APPROVER", selected.get("approver_role_code"));
    }
}
