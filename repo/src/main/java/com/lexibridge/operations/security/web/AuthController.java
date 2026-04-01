package com.lexibridge.operations.security.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AuthController {

    @Value("${lexibridge.bootstrap.enabled:false}")
    private boolean bootstrapEnabled;

    @Value("${lexibridge.bootstrap.admin.username:admin}")
    private String bootstrapAdminUsername;

    @Value("${lexibridge.bootstrap.admin.password:}")
    private String bootstrapAdminPassword;

    @GetMapping("/login")
    public String loginPage(Model model) {
        model.addAttribute("bootstrapEnabled", bootstrapEnabled);
        model.addAttribute("bootstrapAdminUsername", bootstrapAdminUsername);
        model.addAttribute("bootstrapAdminPassword", bootstrapAdminPassword == null ? "" : bootstrapAdminPassword);
        return "login";
    }
}
