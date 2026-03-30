package com.lexibridge.operations.monitoring;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;

@Component
public class LocalTracePersistenceFilter extends OncePerRequestFilter {

    private static final String TRACE_HEADER = "X-Trace-Id";
    private static final String SPAN_HEADER = "X-Span-Id";

    private final TracePersistenceService tracePersistenceService;
    private final SecureRandom secureRandom = new SecureRandom();

    public LocalTracePersistenceFilter(TracePersistenceService tracePersistenceService) {
        this.tracePersistenceService = tracePersistenceService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/images/") || path.equals("/favicon.ico");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = normalizeOrGenerateTraceId(request.getHeader(TRACE_HEADER));
        String spanId = randomHex(8);
        long startNanos = System.nanoTime();
        response.setHeader(TRACE_HEADER, traceId);
        response.setHeader(SPAN_HEADER, spanId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            tracePersistenceService.record(
                traceId,
                spanId,
                request.getMethod(),
                request.getRequestURI(),
                response.getStatus(),
                durationMs,
                "local-filter"
            );
        }
    }

    private String normalizeOrGenerateTraceId(String incoming) {
        if (incoming != null && incoming.matches("[0-9a-fA-F]{32}")) {
            return incoming.toLowerCase();
        }
        return randomHex(16);
    }

    private String randomHex(int byteCount) {
        byte[] bytes = new byte[byteCount];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
