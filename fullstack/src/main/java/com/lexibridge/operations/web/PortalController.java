package com.lexibridge.operations.web;

import com.lexibridge.operations.modules.booking.service.BookingService;
import com.lexibridge.operations.modules.content.service.ContentService;
import com.lexibridge.operations.modules.leave.service.LeaveService;
import com.lexibridge.operations.modules.moderation.service.ModerationService;
import com.lexibridge.operations.modules.payments.service.PaymentsService;
import com.lexibridge.operations.security.service.AuthorizationScopeService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Optional;

@Controller
public class PortalController {

    private final ContentService contentService;
    private final ModerationService moderationService;
    private final BookingService bookingService;
    private final LeaveService leaveService;
    private final PaymentsService paymentsService;
    private final AuthorizationScopeService authorizationScopeService;

    public PortalController(ContentService contentService,
                             ModerationService moderationService,
                             BookingService bookingService,
                             LeaveService leaveService,
                             PaymentsService paymentsService,
                             AuthorizationScopeService authorizationScopeService) {
        this.contentService = contentService;
        this.moderationService = moderationService;
        this.bookingService = bookingService;
        this.leaveService = leaveService;
        this.paymentsService = paymentsService;
        this.authorizationScopeService = authorizationScopeService;
    }

    @GetMapping("/portal")
    public String dashboard(Authentication authentication, Model model) {
        Optional<Long> locationId = authorizationScopeService.currentLocationScope();
        model.addAttribute("username", authentication.getName());
        model.addAttribute("contentSummary", contentService.dashboardSummary(locationId.orElse(null)));
        model.addAttribute("moderationSummary", moderationService.dashboardSummary(locationId.orElse(null)));
        model.addAttribute("bookingSummary", bookingService.dashboardSummary(locationId.orElse(null)));
        model.addAttribute("leaveSummary", leaveService.dashboardSummary(locationId.orElse(null)));
        model.addAttribute("paymentsSummary", paymentsService.dashboardSummary(locationId.orElse(null)));
        return "portal/dashboard";
    }
}
