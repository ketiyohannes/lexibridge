package com.lexibridge.operations.security.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiSecurityFilterTest {

    @Mock
    private HmacAuthService hmacAuthService;
    @Mock
    private ApiRateLimiterService apiRateLimiterService;

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void filter_shouldRejectWhenRateLimitExceededForAuthenticatedUser() throws Exception {
        ApiSecurityFilter filter = new ApiSecurityFilter(hmacAuthService, apiRateLimiterService);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("admin", "N/A", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );
        when(apiRateLimiterService.allow("user:admin")).thenReturn(false);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/content/summary");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(429, response.getStatus());
    }

    @Test
    void filter_shouldAuthenticateHmacClient() throws Exception {
        ApiSecurityFilter filter = new ApiSecurityFilter(hmacAuthService, apiRateLimiterService);
        when(apiRateLimiterService.allow("client:demo-device")).thenReturn(true);
        when(hmacAuthService.authenticate(anyString(), anyInt(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(Optional.of("demo-device"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/content/summary");
        request.addHeader("X-Client-Key", "demo-device");
        request.addHeader("X-Key-Version", "1");
        request.addHeader("X-Timestamp", String.valueOf(System.currentTimeMillis() / 1000));
        request.addHeader("X-Nonce", "abc123");
        request.addHeader("X-Signature", "sig");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }
}
