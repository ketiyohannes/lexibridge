package com.lexibridge.operations.security.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

@Component
public class ApiSecurityFilter extends OncePerRequestFilter {

    private final HmacAuthService hmacAuthService;
    private final ApiRateLimiterService rateLimiterService;

    public ApiSecurityFilter(HmacAuthService hmacAuthService,
                             ApiRateLimiterService rateLimiterService) {
        this.hmacAuthService = hmacAuthService;
        this.rateLimiterService = rateLimiterService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        CachedBodyRequest wrappedRequest = new CachedBodyRequest(request);

        Authentication existingAuth = SecurityContextHolder.getContext().getAuthentication();
        if (existingAuth != null && existingAuth.isAuthenticated() && !(existingAuth instanceof AnonymousAuthenticationToken)) {
            if (!rateLimiterService.allow("user:" + existingAuth.getName())) {
                reject(response, 429, "Rate limit exceeded");
                return;
            }
            filterChain.doFilter(wrappedRequest, response);
            return;
        }

        String clientKey = header(wrappedRequest, "X-Client-Key");
        String keyVersionRaw = header(wrappedRequest, "X-Key-Version");
        String timestampRaw = header(wrappedRequest, "X-Timestamp");
        String nonce = header(wrappedRequest, "X-Nonce");
        String signature = header(wrappedRequest, "X-Signature");

        if (clientKey == null || keyVersionRaw == null || timestampRaw == null || nonce == null || signature == null) {
            reject(response, 401, "Missing HMAC authentication headers");
            return;
        }

        int keyVersion;
        long timestamp;
        try {
            keyVersion = Integer.parseInt(keyVersionRaw);
            timestamp = Long.parseLong(timestampRaw);
        } catch (NumberFormatException ex) {
            reject(response, 401, "Invalid key version or timestamp");
            return;
        }

        if (!rateLimiterService.allow("client:" + clientKey)) {
            reject(response, 429, "Rate limit exceeded");
            return;
        }

        boolean authenticated = hmacAuthService.authenticate(
            clientKey,
            keyVersion,
            timestamp,
            nonce,
            signature,
            wrappedRequest.getMethod(),
            wrappedRequest.getRequestURI(),
            canonicalQuery(wrappedRequest),
            sha256Hex(wrappedRequest.body)
        ).isPresent();

        if (!authenticated) {
            reject(response, 401, "Invalid HMAC credentials");
            return;
        }

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
            clientKey,
            "N/A",
            List.of(new SimpleGrantedAuthority("ROLE_DEVICE_SERVICE"))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
        filterChain.doFilter(wrappedRequest, response);
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }

    private String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? null : value;
    }

    private String canonicalQuery(HttpServletRequest request) {
        String query = request.getQueryString();
        if (query == null || query.isBlank()) {
            return "";
        }
        String[] parts = query.split("&");
        Arrays.sort(parts);
        return String.join("&", parts);
    }

    private String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash request body", ex);
        }
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.body = StreamUtils.copyToByteArray(request.getInputStream());
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return inputStream.read();
                }

                @Override
                public boolean isFinished() {
                    return inputStream.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
