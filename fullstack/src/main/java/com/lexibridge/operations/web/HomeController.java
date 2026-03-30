package com.lexibridge.operations.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @GetMapping({"/", "/health-ui"})
    public String home() {
        return "index";
    }
}
