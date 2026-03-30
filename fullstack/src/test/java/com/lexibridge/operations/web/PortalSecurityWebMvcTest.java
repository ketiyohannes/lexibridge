package com.lexibridge.operations.web;

import com.lexibridge.operations.modules.content.model.ContentImportPreviewResult;
import com.lexibridge.operations.modules.content.service.ContentImportService;
import com.lexibridge.operations.modules.content.service.ContentMediaService;
import com.lexibridge.operations.modules.content.service.ContentService;
import com.lexibridge.operations.modules.leave.service.LeaveService;
import com.lexibridge.operations.modules.leave.web.LeaveController;
import com.lexibridge.operations.modules.moderation.service.ModerationService;
import com.lexibridge.operations.modules.moderation.web.ModerationController;
import com.lexibridge.operations.modules.content.web.ContentController;
import com.lexibridge.operations.modules.booking.web.BookingController;
import com.lexibridge.operations.modules.booking.service.BookingService;
import com.lexibridge.operations.modules.payments.web.PaymentsController;
import com.lexibridge.operations.modules.payments.service.PaymentsService;
import com.lexibridge.operations.modules.admin.web.AdminController;
import com.lexibridge.operations.modules.admin.service.AdminUserManagementService;
import com.lexibridge.operations.modules.admin.service.WebhookSecurityService;
import com.lexibridge.operations.monitoring.TracePersistenceService;
import com.lexibridge.operations.security.api.ApiRateLimiterService;
import com.lexibridge.operations.security.api.HmacAuthService;
import com.lexibridge.operations.security.privacy.PiiMaskingService;
import com.lexibridge.operations.security.service.AuthorizationScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
    ContentController.class,
    ModerationController.class,
    LeaveController.class,
    BookingController.class,
    PaymentsController.class,
    AdminController.class
})
@Import(PortalSecurityWebMvcTest.TestSecurityConfig.class)
class PortalSecurityWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ContentService contentService;
    @MockBean
    private ContentImportService contentImportService;
    @MockBean
    private ContentMediaService contentMediaService;
    @MockBean
    private ModerationService moderationService;
    @MockBean
    private LeaveService leaveService;
    @MockBean
    private BookingService bookingService;
    @MockBean
    private PaymentsService paymentsService;
    @MockBean
    private WebhookSecurityService webhookSecurityService;
    @MockBean
    private AdminUserManagementService adminUserManagementService;
    @MockBean
    private TracePersistenceService tracePersistenceService;
    @MockBean
    private HmacAuthService hmacAuthService;
    @MockBean
    private ApiRateLimiterService apiRateLimiterService;
    @MockBean
    private PiiMaskingService piiMaskingService;
    @MockBean
    private AuthorizationScopeService authorizationScopeService;

    @BeforeEach
    void setUp() {
        when(contentService.dashboardSummary()).thenReturn(Map.of());
        when(contentService.dashboardSummary(org.mockito.ArgumentMatchers.any())).thenReturn(Map.of());
        when(moderationService.dashboardSummary()).thenReturn(Map.of());
        when(moderationService.dashboardSummary(org.mockito.ArgumentMatchers.any())).thenReturn(Map.of());
        when(moderationService.recentCases(anyInt())).thenReturn(List.of());
        when(moderationService.recentCases(org.mockito.ArgumentMatchers.any(), anyInt())).thenReturn(List.of());
        when(leaveService.dashboardSummary()).thenReturn(Map.of());
        when(leaveService.dashboardSummary(org.mockito.ArgumentMatchers.any())).thenReturn(Map.of());
        when(leaveService.recentApprovalQueue(anyInt())).thenReturn(List.of());
        when(leaveService.recentApprovalQueue(org.mockito.ArgumentMatchers.any(), anyInt())).thenReturn(List.of());
        when(bookingService.dashboardSummary()).thenReturn(Map.of());
        when(bookingService.dashboardSummary(org.mockito.ArgumentMatchers.any())).thenReturn(Map.of());
        when(bookingService.latestTimeline()).thenReturn(List.of());
        when(bookingService.latestTimeline(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(paymentsService.dashboardSummary()).thenReturn(Map.of());
        when(paymentsService.dashboardSummary(org.mockito.ArgumentMatchers.any())).thenReturn(Map.of());
        when(paymentsService.exceptions(any())).thenReturn(List.of());
        when(paymentsService.exceptions(anyLong(), org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(paymentsService.recentTenders(anyInt())).thenReturn(List.of());
        when(paymentsService.recentTenders(anyLong(), anyInt())).thenReturn(List.of());
        when(paymentsService.recentRefunds(anyInt())).thenReturn(List.of());
        when(paymentsService.recentRefunds(anyLong(), anyInt())).thenReturn(List.of());
        when(paymentsService.recentReconciliationRuns(anyInt())).thenReturn(List.of());
        when(paymentsService.recentReconciliationRuns(anyLong(), anyInt())).thenReturn(List.of());
        when(webhookSecurityService.activeWebhooks()).thenReturn(List.of());
        when(webhookSecurityService.generateNonce()).thenReturn("abc123");
        when(adminUserManagementService.users(anyInt())).thenReturn(List.of());
        when(tracePersistenceService.latest(anyInt())).thenReturn(List.of());
        when(authorizationScopeService.requireCurrentUserId()).thenReturn(1L);
        when(authorizationScopeService.currentLocationScope()).thenReturn(Optional.of(1L));
    }

    @Test
    @WithMockUser(roles = "CONTENT_EDITOR")
    void contentPage_allowsContentEditorRole() throws Exception {
        mockMvc.perform(get("/portal/content"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "CONTENT_EDITOR")
    void moderationPage_blocksWrongRole() throws Exception {
        mockMvc.perform(get("/portal/moderation"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MODERATOR")
    void moderationPage_allowsModeratorRole() throws Exception {
        mockMvc.perform(get("/portal/moderation"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "CONTENT_EDITOR")
    void contentPreviewPost_requiresCsrf() throws Exception {
        mockMvc.perform(
                multipart("/portal/content/imports/preview")
                    .file("file", "term,category\nhello,VOCABULARY\n".getBytes())
                    .param("locationId", "1")
                    .param("uploadedBy", "1")
                    .param("format", "csv")
            )
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "CONTENT_EDITOR")
    void contentPreviewPost_acceptsCsrf() throws Exception {
        when(contentImportService.preview(anyLong(), anyLong(), anyString(), anyString(), any()))
            .thenReturn(new ContentImportPreviewResult(11L, 1, 0, 1, 0, List.of()));

        mockMvc.perform(
                multipart("/portal/content/imports/preview")
                    .file("file", "term,category\nhello,VOCABULARY\n".getBytes())
                    .param("locationId", "1")
                    .param("uploadedBy", "1")
                    .param("format", "csv")
                    .with(csrf())
            )
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "MODERATOR")
    void moderationResolvePost_requiresCsrf() throws Exception {
        mockMvc.perform(
                post("/portal/moderation/cases/5/resolve")
                    .param("reviewerUserId", "9")
                    .param("offenderUserId", "22")
                    .param("reason", "policy violation")
                    .param("decision", "REJECTED")
            )
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MODERATOR")
    void moderationResolvePost_acceptsCsrfForModerator() throws Exception {
        when(moderationService.resolveCase(anyLong(), anyString(), anyLong(), anyLong(), anyString(), anyString()))
            .thenReturn(Map.of("decision", "APPROVED"));
        when(moderationService.caseDetails(anyLong()))
            .thenReturn(Map.of("id", 5L, "target_type", "POST", "target_id", 99L, "status", "APPROVED", "sensitive_hits_json", "[]"));

        mockMvc.perform(
                post("/portal/moderation/cases/5/resolve")
                    .with(csrf())
                    .param("reviewerUserId", "9")
                    .param("offenderUserId", "22")
                    .param("reason", "clean")
                    .param("decision", "APPROVED")
            )
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void moderationReportSubmit_shouldAllowAuthenticatedReporter() throws Exception {
        when(moderationService.submitUserReport(anyLong(), anyLong(), anyString(), anyLong(), anyString()))
            .thenReturn(Map.of("reportId", 12L, "disposition", "OPEN"));
        when(moderationService.reportsByReporter(anyLong())).thenReturn(List.of());
        when(moderationService.penaltiesForUser(anyLong())).thenReturn(List.of());

        mockMvc.perform(
                post("/portal/moderation/reports")
                    .with(csrf())
                    .param("locationId", "1")
                    .param("targetType", "POST")
                    .param("targetId", "22")
                    .param("reasonText", "abuse")
            )
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void leaveRequestSubmit_requiresCsrf() throws Exception {
        mockMvc.perform(
                post("/portal/leave/requests")
                    .param("locationId", "1")
                    .param("requesterUserId", "7")
                    .param("leaveType", "ANNUAL_LEAVE")
                    .param("startDate", "2026-04-03")
                    .param("endDate", "2026-04-03")
                    .param("durationMinutes", "480")
            )
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void leaveRequestSubmit_acceptsCsrfForEmployee() throws Exception {
        when(leaveService.submit(any())).thenReturn(Map.of("leaveRequestId", 77L));
        when(leaveService.recentRequestsByRequester(anyLong(), anyInt())).thenReturn(List.of());

        mockMvc.perform(
                post("/portal/leave/requests")
                    .with(csrf())
                    .param("locationId", "1")
                    .param("requesterUserId", "7")
                    .param("leaveType", "ANNUAL_LEAVE")
                    .param("startDate", "2026-04-03")
                    .param("endDate", "2026-04-03")
                    .param("durationMinutes", "480")
            )
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void leaveApprove_shouldDenyEmployeeRole() throws Exception {
        mockMvc.perform(
                post("/portal/leave/approvals/11/approve")
                    .with(csrf())
                    .param("note", "approve")
            )
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void leaveAttachmentLookup_shouldAllowApproverRole() throws Exception {
        when(leaveService.attachments(20L)).thenReturn(List.of());

        mockMvc.perform(
                post("/portal/leave/requests/20/attachments/lookup")
                    .with(csrf())
            )
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void leaveAttachmentLookup_shouldReturnForbiddenWhenReadScopeViolation() throws Exception {
        doThrow(new org.springframework.security.access.AccessDeniedException("out of scope"))
            .when(authorizationScopeService).assertLeaveRequestReadAccess(20L);

        mockMvc.perform(
                post("/portal/leave/requests/20/attachments/lookup")
                    .with(csrf())
            )
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void leaveAttachmentDownload_shouldReturnForbiddenWhenReadScopeViolation() throws Exception {
        doThrow(new org.springframework.security.access.AccessDeniedException("out of scope"))
            .when(authorizationScopeService).assertLeaveRequestReadAccess(20L);

        mockMvc.perform(get("/portal/leave/requests/20/attachments/3/download"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "FRONT_DESK")
    void bookingsPage_allowsFrontDeskRole() throws Exception {
        mockMvc.perform(get("/portal/bookings"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "FRONT_DESK")
    void bookingPrintPage_allowsFrontDeskRole() throws Exception {
        when(bookingService.printableCard(anyLong(), anyLong())).thenReturn(Map.of(
            "bookingId", 7L,
            "status", "CONFIRMED",
            "customerName", "A***x",
            "customerPhone", "***0100",
            "startAt", "2026-04-01T10:00:00",
            "endAt", "2026-04-01T10:30:00",
            "qrImageUrl", "/portal/bookings/7/qr?token=abc",
            "qrToken", "abc"
        ));
        mockMvc.perform(get("/portal/bookings/7/print"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("A***x")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("***0100")))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Alex"))))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("5550100"))));
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void bookingPrintPage_deniesNonFrontDeskRole() throws Exception {
        mockMvc.perform(get("/portal/bookings/7/print"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "FRONT_DESK")
    void bookingsReservePost_requiresCsrf() throws Exception {
        mockMvc.perform(
                post("/portal/bookings/reserve")
                    .param("locationId", "1")
                    .param("createdBy", "8")
                    .param("customerName", "Alex")
                    .param("startAt", "2026-04-04T10:00:00")
                    .param("durationMinutes", "60")
            )
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "FRONT_DESK")
    void bookingsReservePost_shouldReturnForbiddenWhenLocationOutOfScope() throws Exception {
        doThrow(new org.springframework.security.access.AccessDeniedException("out of scope"))
            .when(authorizationScopeService).assertLocationAccess(9L);

        mockMvc.perform(
                post("/portal/bookings/reserve")
                    .with(csrf())
                    .param("locationId", "9")
                    .param("createdBy", "8")
                    .param("customerName", "Alex")
                    .param("startAt", "2026-04-04T10:00:00")
                    .param("durationMinutes", "60")
            )
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void leaveRequestSubmit_shouldReturnForbiddenWhenLocationOutOfScope() throws Exception {
        doThrow(new org.springframework.security.access.AccessDeniedException("out of scope"))
            .when(authorizationScopeService).assertLocationAccess(8L);

        mockMvc.perform(
                post("/portal/leave/requests")
                    .with(csrf())
                    .param("locationId", "8")
                    .param("requesterUserId", "7")
                    .param("leaveType", "ANNUAL_LEAVE")
                    .param("startDate", "2026-04-03")
                    .param("endDate", "2026-04-03")
                    .param("durationMinutes", "480")
            )
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SUPERVISOR")
    void paymentsPage_allowsSupervisorRole() throws Exception {
        mockMvc.perform(get("/portal/payments"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "SUPERVISOR")
    void paymentsExceptionAction_requiresCsrf() throws Exception {
        mockMvc.perform(
                post("/portal/payments/exceptions/3/resolve")
                    .param("actorUserId", "1")
                    .param("note", "ok")
            )
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "FRONT_DESK")
    void refundApprove_shouldDenyFrontDeskRole() throws Exception {
        mockMvc.perform(
                post("/portal/payments/refunds/4/approve")
                    .with(csrf())
            )
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "FRONT_DESK")
    void bookingReschedule_shouldReturnForbiddenWhenOutOfScope() throws Exception {
        doThrow(new org.springframework.security.access.AccessDeniedException("out of scope"))
            .when(authorizationScopeService).assertBookingAccess(9L);

        mockMvc.perform(
                post("/portal/bookings/9/reschedule")
                    .with(csrf())
                    .param("startAt", "2026-04-04T10:00:00")
                    .param("durationMinutes", "60")
                    .param("reason", "Move")
            )
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "CONTENT_EDITOR")
    void contentImportPreview_shouldReturnForbiddenWhenLocationOutOfScope() throws Exception {
        doThrow(new org.springframework.security.access.AccessDeniedException("out of scope"))
            .when(authorizationScopeService).assertLocationAccess(2L);

        mockMvc.perform(
                multipart("/portal/content/imports/preview")
                    .file("file", "term,category\nhello,VOCABULARY\n".getBytes())
                    .param("locationId", "2")
                    .param("uploadedBy", "1")
                    .param("format", "csv")
                    .with(csrf())
            )
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminPage_allowsAdminRole() throws Exception {
        mockMvc.perform(get("/portal/admin"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "MODERATOR")
    void adminPage_blocksNonAdminRole() throws Exception {
        mockMvc.perform(get("/portal/admin"))
            .andExpect(status().isForbidden());
    }

    @TestConfiguration
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/login", "/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/css/**", "/js/**", "/images/**").permitAll()
                .requestMatchers("/portal/admin", "/portal/admin/**").hasAnyRole("ADMIN")
                .requestMatchers("/portal/content", "/portal/content/**").hasAnyRole("ADMIN", "CONTENT_EDITOR")
                .requestMatchers("/portal/moderation/reports").authenticated()
                .requestMatchers("/portal/moderation", "/portal/moderation/**").hasAnyRole("ADMIN", "MODERATOR")
                .requestMatchers("/portal/bookings", "/portal/bookings/**").hasAnyRole("ADMIN", "FRONT_DESK")
                .requestMatchers("/portal/leave", "/portal/leave/**").hasAnyRole("ADMIN", "EMPLOYEE", "MANAGER", "HR_APPROVER")
                .requestMatchers("/portal/payments", "/portal/payments/**").hasAnyRole("ADMIN", "SUPERVISOR", "FRONT_DESK")
                .requestMatchers("/portal").authenticated()
                .anyRequest().authenticated());
            http.formLogin(form -> form.loginPage("/login").permitAll());
            return http.build();
        }

        @Bean
        UserDetailsService userDetailsService() {
            return new InMemoryUserDetailsManager(
                User.withUsername("admin").password("{noop}admin").roles("ADMIN").build()
            );
        }
    }
}
