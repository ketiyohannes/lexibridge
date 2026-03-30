package com.lexibridge.operations.web;

import com.lexibridge.operations.modules.admin.api.AdminApiController;
import com.lexibridge.operations.modules.admin.service.AdminUserManagementService;
import com.lexibridge.operations.modules.admin.service.WebhookDeliveryService;
import com.lexibridge.operations.modules.admin.service.WebhookSecurityService;
import com.lexibridge.operations.modules.booking.api.BookingApiController;
import com.lexibridge.operations.modules.booking.service.BookingService;
import com.lexibridge.operations.modules.content.api.ContentApiController;
import com.lexibridge.operations.modules.content.service.ContentImportService;
import com.lexibridge.operations.modules.content.service.ContentMediaService;
import com.lexibridge.operations.modules.content.service.ContentService;
import com.lexibridge.operations.modules.content.service.MediaValidationService;
import com.lexibridge.operations.monitoring.TracePersistenceService;
import com.lexibridge.operations.modules.leave.api.LeaveApiController;
import com.lexibridge.operations.modules.leave.service.LeaveService;
import com.lexibridge.operations.modules.moderation.api.ModerationApiController;
import com.lexibridge.operations.modules.moderation.service.ModerationService;
import com.lexibridge.operations.modules.payments.api.PaymentsApiController;
import com.lexibridge.operations.modules.payments.service.PaymentsService;
import com.lexibridge.operations.security.api.ApiRateLimiterService;
import com.lexibridge.operations.security.api.HmacAuthService;
import com.lexibridge.operations.security.service.AuthorizationScopeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
    AdminApiController.class,
    BookingApiController.class,
    ContentApiController.class,
    LeaveApiController.class,
    ModerationApiController.class,
    PaymentsApiController.class
})
@Import(ApiAuthorizationWebMvcTest.TestSecurityConfig.class)
class ApiAuthorizationWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WebhookSecurityService webhookSecurityService;
    @MockBean
    private WebhookDeliveryService webhookDeliveryService;
    @MockBean
    private AdminUserManagementService adminUserManagementService;
    @MockBean
    private BookingService bookingService;
    @MockBean
    private ContentService contentService;
    @MockBean
    private ContentImportService contentImportService;
    @MockBean
    private ContentMediaService contentMediaService;
    @MockBean
    private MediaValidationService mediaValidationService;
    @MockBean
    private TracePersistenceService tracePersistenceService;
    @MockBean
    private LeaveService leaveService;
    @MockBean
    private ModerationService moderationService;
    @MockBean
    private PaymentsService paymentsService;
    @MockBean
    private AuthorizationScopeService authorizationScopeService;
    @MockBean
    private HmacAuthService hmacAuthService;
    @MockBean
    private ApiRateLimiterService apiRateLimiterService;

    @BeforeEach
    void setUp() {
        when(apiRateLimiterService.allow(anyString())).thenReturn(true);
    }

    @Test
    @WithMockUser(roles = "MODERATOR")
    void adminApi_shouldRejectNonAdminRole() throws Exception {
        mockMvc.perform(get("/api/v1/admin/status"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "DEVICE_SERVICE")
    void paymentsCallback_shouldAllowDeviceRole() throws Exception {
        when(authorizationScopeService.currentLocationScope()).thenReturn(java.util.Optional.of(2L));
        when(paymentsService.processCallback("T", "X", Map.of(), 2L, "user")).thenReturn(Map.of("status", "PROCESSED"));
        mockMvc.perform(post("/api/v1/payments/callbacks")
                .with(csrf())
                .contentType("application/json")
                .content("{\"terminalId\":\"T\",\"terminalTxnId\":\"X\",\"payload\":{}}"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "FRONT_DESK")
    void paymentsCallback_shouldRequireLocationScopeForNonAdminActor() throws Exception {
        when(authorizationScopeService.currentLocationScope()).thenReturn(java.util.Optional.empty());

        mockMvc.perform(post("/api/v1/payments/callbacks")
                .with(csrf())
                .contentType("application/json")
                .content("{\"terminalId\":\"T\",\"terminalTxnId\":\"X\",\"payload\":{}}"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "FRONT_DESK")
    void paymentsCallback_shouldForwardScopedLocationForNonAdminActor() throws Exception {
        when(authorizationScopeService.currentLocationScope()).thenReturn(java.util.Optional.of(7L));
        when(paymentsService.processCallback("T", "X", Map.of(), 7L, "user")).thenReturn(Map.of("status", "PROCESSED"));

        mockMvc.perform(post("/api/v1/payments/callbacks")
                .with(csrf())
                .contentType("application/json")
                .content("{\"terminalId\":\"T\",\"terminalTxnId\":\"X\",\"payload\":{}}"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "DEVICE_SERVICE")
    void paymentsTenderCreate_shouldDenyDeviceRole() throws Exception {
        mockMvc.perform(post("/api/v1/payments/tenders")
                .with(csrf())
                .contentType("application/json")
                .content("{\"bookingOrderId\":77,\"tenderType\":\"CARD\",\"amount\":10.00,\"currency\":\"USD\",\"createdBy\":1}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void paymentsCallback_shouldAllowHmacWithoutCsrfToken() throws Exception {
        when(hmacAuthService.authenticate(anyString(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(java.util.Optional.of("demo-device"));
        when(authorizationScopeService.currentLocationScope()).thenReturn(java.util.Optional.of(2L));
        when(paymentsService.processCallback("T", "X", Map.of(), 2L, "demo-device")).thenReturn(Map.of("status", "PROCESSED"));

        mockMvc.perform(post("/api/v1/payments/callbacks")
                .header("X-Client-Key", "demo-device")
                .header("X-Key-Version", "1")
                .header("X-Timestamp", String.valueOf(System.currentTimeMillis() / 1000))
                .header("X-Nonce", "n-123")
                .header("X-Signature", "sig")
                .contentType("application/json")
                .content("{\"terminalId\":\"T\",\"terminalTxnId\":\"X\",\"payload\":{}}"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void leaveWithdraw_shouldReturnForbiddenOnOwnershipViolation() throws Exception {
        doThrow(new org.springframework.security.access.AccessDeniedException("out of scope"))
            .when(authorizationScopeService).assertLeaveRequestRequester(10L);
        mockMvc.perform(post("/api/v1/leave/requests/10/withdraw").with(csrf()))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void leaveWithdraw_shouldReturnConflictWhenAlreadyFinalized() throws Exception {
        when(leaveService.withdraw(10L)).thenReturn(false);

        mockMvc.perform(post("/api/v1/leave/requests/10/withdraw").with(csrf()))
            .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void leaveResubmit_shouldReturnForbiddenOnOwnershipViolation() throws Exception {
        doThrow(new org.springframework.security.access.AccessDeniedException("out of scope"))
            .when(authorizationScopeService).assertLeaveRequestRequester(11L);

        mockMvc.perform(post("/api/v1/leave/requests/11/resubmit")
                .with(csrf())
                .contentType("application/json")
                .content("{\"locationId\":1,\"requesterUserId\":9,\"leaveType\":\"ANNUAL_LEAVE\",\"startDate\":\"2026-05-01\",\"endDate\":\"2026-05-01\",\"durationMinutes\":60}"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void leaveFormDefinitionCreate_shouldDenyEmployeeRole() throws Exception {
        mockMvc.perform(post("/api/v1/leave/forms/definitions")
                .with(csrf())
                .contentType("application/json")
                .content("{\"locationId\":1,\"name\":\"Default Leave Form\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void leaveFormDefinitionCreate_shouldUseAuthenticatedActorIdentity() throws Exception {
        when(authorizationScopeService.requireCurrentUserId()).thenReturn(44L);
        when(leaveService.createFormDefinition(1L, "Default Leave Form", 44L))
            .thenReturn(Map.of("formDefinitionId", 5L, "status", "CREATED"));

        mockMvc.perform(post("/api/v1/leave/forms/definitions")
                .with(csrf())
                .contentType("application/json")
                .content("{\"locationId\":1,\"name\":\"Default Leave Form\"}"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void leaveApprove_shouldRejectForgedApproverUserIdFieldInPayload() throws Exception {
        mockMvc.perform(post("/api/v1/leave/approvals/12/approve")
                .with(csrf())
                .contentType("application/json")
                .content("{\"approverUserId\":999,\"note\":\"ok\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void leaveApprove_shouldUseAuthenticatedActorIdentity() throws Exception {
        when(authorizationScopeService.requireCurrentUserId()).thenReturn(44L);
        when(leaveService.approveTask(12L, 44L, "ok")).thenReturn(Map.of("taskId", 12L, "status", "APPROVED"));

        mockMvc.perform(post("/api/v1/leave/approvals/12/approve")
                .with(csrf())
                .contentType("application/json")
                .content("{\"note\":\"ok\"}"))
            .andExpect(status().isOk());

        verify(leaveService).approveTask(12L, 44L, "ok");
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void leaveCorrection_shouldRejectForgedApproverUserIdFieldInPayload() throws Exception {
        mockMvc.perform(post("/api/v1/leave/approvals/12/correction")
                .with(csrf())
                .contentType("application/json")
                .content("{\"approverUserId\":999,\"note\":\"needs changes\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void leaveCorrection_shouldUseAuthenticatedActorIdentity() throws Exception {
        when(authorizationScopeService.requireCurrentUserId()).thenReturn(44L);
        when(leaveService.requestCorrection(12L, 44L, "needs changes"))
            .thenReturn(Map.of("taskId", 12L, "status", "CORRECTION_REQUESTED"));

        mockMvc.perform(post("/api/v1/leave/approvals/12/correction")
                .with(csrf())
                .contentType("application/json")
                .content("{\"note\":\"needs changes\"}"))
            .andExpect(status().isOk());

        verify(leaveService).requestCorrection(12L, 44L, "needs changes");
    }

    @Test
    @WithMockUser(roles = "FRONT_DESK")
    void bookingReserve_shouldReturnForbiddenOnLocationViolation() throws Exception {
        doThrow(new org.springframework.security.access.AccessDeniedException("out of scope"))
            .when(authorizationScopeService).assertLocationAccess(2L);

        mockMvc.perform(post("/api/v1/bookings")
                .with(csrf())
                .contentType("application/json")
                .content("{\"locationId\":2,\"createdBy\":1,\"customerName\":\"A\",\"startAt\":\"2026-04-01T10:00:00\",\"durationMinutes\":30}"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "FRONT_DESK")
    void bookingSummary_shouldUseScopedLocationForNonAdmin() throws Exception {
        when(authorizationScopeService.currentLocationScope()).thenReturn(java.util.Optional.of(2L));
        when(bookingService.dashboardSummary(2L)).thenReturn(Map.of("reservedNow", 1));

        mockMvc.perform(get("/api/v1/bookings/summary"))
            .andExpect(status().isOk());

        verify(bookingService).dashboardSummary(2L);
        verify(bookingService, never()).dashboardSummary((Long) null);
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void leaveSummary_shouldUseScopedLocationForNonAdmin() throws Exception {
        when(authorizationScopeService.currentLocationScope()).thenReturn(java.util.Optional.of(3L));
        when(leaveService.dashboardSummary(3L)).thenReturn(Map.of("pendingApprovals", 2));

        mockMvc.perform(get("/api/v1/leave/summary"))
            .andExpect(status().isOk());

        verify(leaveService).dashboardSummary(3L);
        verify(leaveService, never()).dashboardSummary((Long) null);
    }

    @Test
    @WithMockUser(roles = "MODERATOR")
    void moderationSummary_shouldUseScopedLocationForNonAdmin() throws Exception {
        when(authorizationScopeService.currentLocationScope()).thenReturn(java.util.Optional.of(6L));
        when(moderationService.dashboardSummary(6L)).thenReturn(Map.of("pendingCount", 3));

        mockMvc.perform(get("/api/v1/moderation/summary"))
            .andExpect(status().isOk());

        verify(moderationService).dashboardSummary(6L);
        verify(moderationService, never()).dashboardSummary();
    }

    @Test
    @WithMockUser(roles = "MODERATOR")
    void moderationSummary_shouldReturnForbiddenWhenLocationScopeMissing() throws Exception {
        when(authorizationScopeService.currentLocationScope()).thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/api/v1/moderation/summary"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SUPERVISOR")
    void paymentsSummary_shouldUseScopedLocationForNonAdmin() throws Exception {
        when(authorizationScopeService.currentLocationScope()).thenReturn(java.util.Optional.of(4L));
        when(paymentsService.dashboardSummary(4L)).thenReturn(Map.of("tendersToday", 4));

        mockMvc.perform(get("/api/v1/payments/summary"))
            .andExpect(status().isOk());

        verify(paymentsService).dashboardSummary(4L);
        verify(paymentsService, never()).dashboardSummary((Long) null);
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void moderationReportSubmit_shouldAllowAuthenticatedReporter() throws Exception {
        when(moderationService.submitUserReport(eq(9L), eq(1L), eq("POST"), eq(77L), eq("abuse")))
            .thenReturn(Map.of("reportId", 101L, "disposition", "OPEN"));

        mockMvc.perform(post("/api/v1/moderation/reports")
                .with(csrf())
                .contentType("application/json")
                .content("{\"locationId\":1,\"reporterUserId\":9,\"targetType\":\"POST\",\"targetId\":77,\"reasonText\":\"abuse\"}"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void moderationCommunityPostCreate_shouldAllowAuthenticatedUser() throws Exception {
        when(authorizationScopeService.requireCurrentUserId()).thenReturn(9L);
        when(moderationService.createPostTarget(1L, 9L, "Hello", "<p>Body</p>")).thenReturn(101L);

        mockMvc.perform(post("/api/v1/moderation/community/posts")
                .with(csrf())
                .contentType("application/json")
                .content("{\"locationId\":1,\"title\":\"Hello\",\"bodyHtml\":\"<p>Body</p>\"}"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void leaveAttachmentDownload_shouldReturnForbiddenWhenReadScopeViolation() throws Exception {
        doThrow(new org.springframework.security.access.AccessDeniedException("out of scope"))
            .when(authorizationScopeService).assertLeaveRequestReadAccess(20L);

        mockMvc.perform(get("/api/v1/leave/requests/20/attachments/3/download"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void moderationReportResolve_shouldDenyNonModeratorRole() throws Exception {
        mockMvc.perform(post("/api/v1/moderation/reports/10/resolve")
                .with(csrf())
                .contentType("application/json")
                .content("{\"disposition\":\"DISMISSED\",\"moderatorUserId\":1}"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "CONTENT_EDITOR")
    void contentPublish_shouldReturnForbiddenOnScopeViolation() throws Exception {
        doThrow(new org.springframework.security.access.AccessDeniedException("out of scope"))
            .when(authorizationScopeService).assertContentItemScope(15L);

        mockMvc.perform(post("/api/v1/content/items/15/publish").with(csrf()))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "FRONT_DESK")
    void paymentsTenderCreate_shouldReturnForbiddenOnBookingScopeViolation() throws Exception {
        doThrow(new org.springframework.security.access.AccessDeniedException("out of scope"))
            .when(authorizationScopeService).assertBookingAccess(77L);

        mockMvc.perform(post("/api/v1/payments/tenders")
                .with(csrf())
                .contentType("application/json")
                .content("{\"bookingOrderId\":77,\"tenderType\":\"CARD_PRESENT\",\"amount\":10.00,\"currency\":\"USD\",\"createdBy\":1}"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "FRONT_DESK")
    void paymentsTenderCreate_shouldRequireCsrfForSessionAuthenticatedApiCalls() throws Exception {
        mockMvc.perform(post("/api/v1/payments/tenders")
                .contentType("application/json")
                .content("{\"bookingOrderId\":77,\"tenderType\":\"CARD_PRESENT\",\"amount\":10.00,\"currency\":\"USD\",\"createdBy\":1}"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "CONTENT_EDITOR")
    void contentMediaDownload_shouldReturnForbiddenOnScopeViolation() throws Exception {
        doThrow(new org.springframework.security.access.AccessDeniedException("out of scope"))
            .when(authorizationScopeService).assertContentItemScope(15L);

        mockMvc.perform(get("/api/v1/content/items/15/media/2/download"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "FRONT_DESK")
    void bookingAttachmentDownload_shouldReturnForbiddenOnScopeViolation() throws Exception {
        doThrow(new org.springframework.security.access.AccessDeniedException("out of scope"))
            .when(authorizationScopeService).assertBookingAccess(77L);

        mockMvc.perform(get("/api/v1/bookings/77/attachments/2/download"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void leaveAttachmentDownload_shouldReturnForbiddenOnScopeViolation() throws Exception {
        doThrow(new org.springframework.security.access.AccessDeniedException("out of scope"))
            .when(authorizationScopeService).assertLeaveRequestReadAccess(20L);

        mockMvc.perform(get("/api/v1/leave/requests/20/attachments/3/download"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MODERATOR")
    void moderationMediaDownload_shouldReturnForbiddenOnScopeViolation() throws Exception {
        doThrow(new org.springframework.security.access.AccessDeniedException("out of scope"))
            .when(authorizationScopeService).assertModerationCaseScope(9L);

        mockMvc.perform(get("/api/v1/moderation/cases/9/media/1/download"))
            .andExpect(status().isForbidden());
    }

    @TestConfiguration
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                HmacAuthService hmacAuthService,
                                                ApiRateLimiterService apiRateLimiterService) throws Exception {
            http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/moderation/community/**").authenticated()
                .requestMatchers("/api/v1/moderation/reports", "/api/v1/moderation/reports/by-reporter/**", "/api/v1/moderation/penalties/**").authenticated()
                .requestMatchers("/api/v1/moderation/**").hasAnyRole("ADMIN", "MODERATOR")
                .requestMatchers("/api/v1/leave/**").hasAnyRole("ADMIN", "EMPLOYEE", "MANAGER", "HR_APPROVER")
                .requestMatchers("/api/v1/payments/callbacks").hasAnyRole("ADMIN", "SUPERVISOR", "FRONT_DESK", "DEVICE_SERVICE")
                .requestMatchers("/api/v1/payments/**").hasAnyRole("ADMIN", "SUPERVISOR", "FRONT_DESK")
                .requestMatchers("/api/v1/bookings/**").hasAnyRole("ADMIN", "FRONT_DESK", "DEVICE_SERVICE")
                .requestMatchers("/api/v1/content/**").hasAnyRole("ADMIN", "CONTENT_EDITOR", "DEVICE_SERVICE")
                .anyRequest().authenticated());
            http.csrf(csrf -> csrf.ignoringRequestMatchers(request ->
                request.getRequestURI().startsWith("/api/")
                    && request.getHeader("X-Client-Key") != null
                    && !request.getHeader("X-Client-Key").isBlank()
            ));
            http.addFilterBefore(new com.lexibridge.operations.security.api.ApiSecurityFilter(
                hmacAuthService,
                apiRateLimiterService
            ), UsernamePasswordAuthenticationFilter.class);
            return http.build();
        }

        @Bean
        UserDetailsService userDetailsService() {
            return new InMemoryUserDetailsManager(List.of(
                User.withUsername("admin").password("{noop}x").roles("ADMIN").build(),
                User.withUsername("device").password("{noop}x").roles("DEVICE_SERVICE").build()
            ));
        }
    }
}
