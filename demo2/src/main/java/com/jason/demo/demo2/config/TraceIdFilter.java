package com.jason.demo.demo2.config;

import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 将当前请求的 traceId 写入响应头，便于在 Grafana Tempo 中按 X-Trace-Id 检索链路。
 * 由 {@code app.trace.response-header.enabled} 控制是否启用。
 */
@Component
@ConditionalOnProperty(name = "app.trace.response-header.enabled", havingValue = "true")
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final Tracer tracer;

    public TraceIdFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = currentTraceId();
        if (traceId != null) {
            response.setHeader(TRACE_ID_HEADER, traceId);
        }
        filterChain.doFilter(request, response);
    }

    private String currentTraceId() {
        TraceContext context = tracer.currentTraceContext().context();
        return context != null ? context.traceId() : null;
    }
}
