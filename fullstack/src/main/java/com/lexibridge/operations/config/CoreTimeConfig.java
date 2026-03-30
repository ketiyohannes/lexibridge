package com.lexibridge.operations.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class CoreTimeConfig {

    @Bean
    public Clock applicationClock() {
        return Clock.systemUTC();
    }
}
