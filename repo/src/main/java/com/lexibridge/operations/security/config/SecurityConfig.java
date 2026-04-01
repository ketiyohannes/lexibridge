package com.lexibridge.operations.security.config;

import com.lexibridge.operations.security.api.ApiSecurityFilter;
import com.lexibridge.operations.security.service.DatabaseUserDetailsService;
import com.lexibridge.operations.security.web.LoginFailureHandler;
import com.lexibridge.operations.security.web.LoginSuccessHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   LoginSuccessHandler loginSuccessHandler,
                                                   LoginFailureHandler loginFailureHandler,
                                                   DaoAuthenticationProvider authenticationProvider,
                                                   ApiSecurityFilter apiSecurityFilter) throws Exception {
        http.authenticationProvider(authenticationProvider);

        http.authorizeHttpRequests(auth -> auth
            .requestMatchers("/", "/login", "/actuator/health", "/actuator/info").permitAll()
            .requestMatchers("/actuator/prometheus").hasRole("ADMIN")
            .requestMatchers("/css/**", "/js/**", "/images/**").permitAll()
            .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
            .requestMatchers("/api/v1/moderation/community/**").authenticated()
            .requestMatchers("/api/v1/moderation/reports", "/api/v1/moderation/reports/by-reporter/**", "/api/v1/moderation/penalties/**").authenticated()
            .requestMatchers("/api/v1/moderation/**").hasAnyRole("ADMIN", "MODERATOR")
            .requestMatchers("/api/v1/leave/**").hasAnyRole("ADMIN", "EMPLOYEE", "MANAGER", "HR_APPROVER")
            .requestMatchers("/api/v1/payments/callbacks").hasAnyRole("ADMIN", "SUPERVISOR", "FRONT_DESK", "DEVICE_SERVICE")
            .requestMatchers("/api/v1/payments/**").hasAnyRole("ADMIN", "SUPERVISOR", "FRONT_DESK")
            .requestMatchers("/api/v1/bookings/**").hasAnyRole("ADMIN", "FRONT_DESK", "DEVICE_SERVICE")
            .requestMatchers("/api/v1/content/**").hasAnyRole("ADMIN", "CONTENT_EDITOR", "DEVICE_SERVICE")
            .requestMatchers("/portal/admin", "/portal/admin/**").hasAnyRole("ADMIN")
            .requestMatchers("/portal/content", "/portal/content/**").hasAnyRole("ADMIN", "CONTENT_EDITOR")
            .requestMatchers("/portal/moderation/reports").authenticated()
            .requestMatchers("/portal/moderation", "/portal/moderation/**").hasAnyRole("ADMIN", "MODERATOR")
            .requestMatchers("/portal/bookings", "/portal/bookings/**").hasAnyRole("ADMIN", "FRONT_DESK")
            .requestMatchers("/portal/leave", "/portal/leave/**").hasAnyRole("ADMIN", "EMPLOYEE", "MANAGER", "HR_APPROVER")
            .requestMatchers("/portal/payments", "/portal/payments/**").hasAnyRole("ADMIN", "SUPERVISOR", "FRONT_DESK")
            .requestMatchers("/portal").authenticated()
            .anyRequest().authenticated());

        http.formLogin(form -> form
            .loginPage("/login")
            .successHandler(loginSuccessHandler)
            .failureHandler(loginFailureHandler)
            .permitAll());

        http.logout(logout -> logout
            .logoutUrl("/logout")
            .logoutSuccessUrl("/login?logout")
            .invalidateHttpSession(true)
            .deleteCookies("JSESSIONID"));

        http.exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
            new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
            new AntPathRequestMatcher("/actuator/**")
        ));

        http.addFilterBefore(apiSecurityFilter, UsernamePasswordAuthenticationFilter.class);
        http.csrf(csrf -> csrf.ignoringRequestMatchers(request ->
            request.getRequestURI().startsWith("/api/")
                && request.getHeader("X-Client-Key") != null
                && !request.getHeader("X-Client-Key").isBlank()
        ));

        return http.build();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider(DatabaseUserDetailsService userDetailsService,
                                                            PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
