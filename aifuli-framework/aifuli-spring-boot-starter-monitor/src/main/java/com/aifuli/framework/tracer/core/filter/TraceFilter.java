package com.aifuli.framework.tracer.core.filter;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.aifuli.framework.common.util.monitor.TracerUtils;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 增强型 Trace 过滤器：
 * 1. 优先提取请求头中的链路追踪编号 (trace-id)，实现网关与微服务间跨进程继承；
 * 2. 其次获取 SkyWalking JavaAgent 注入的 TraceId；
 * 3. 若均未命中（如离线/未挂载 Agent 环境），兜底生成轻量分布式唯一 ID；
 * 4. 存入 SLF4J MDC 供 Logback 结构化日志抓取，并写入响应 Header；
 * 5. 请求结束时严格清理 MDC，防止 Servlet 容器工作线程池复用污染。
 *
 * 设计见 {@code docs/superpowers/specs/2026-10-04-elk-logging-integration-design.md#41-tracefilter-改造与-mdc-注入规范}
 *
 * @author 芋道源码
 */
public class TraceFilter extends OncePerRequestFilter {

    /**
     * Header 名 - 链路追踪编号
     */
    public static final String HEADER_NAME_TRACE_ID = "trace-id";

    /**
     * MDC Key - 链路追踪编号
     */
    public static final String MDC_KEY_TRACE_ID = TracerUtils.MDC_KEY_TRACE_ID;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        // 1. 优先从请求头提取已有的 trace-id (如来自网关或上游微服务)
        String traceId = request.getHeader(HEADER_NAME_TRACE_ID);
        // 2. 其次从 SkyWalking 上下文提取
        if (StrUtil.isBlank(traceId)) {
            traceId = TracerUtils.getTraceId();
        }
        // 3. 兜底自动生成全局唯一 traceId (无 Agent 离线环境)
        if (StrUtil.isBlank(traceId)) {
            traceId = IdUtil.fastSimpleUUID();
        }

        // 4. 双写：写入 SLF4J MDC 与 HTTP 响应头
        MDC.put(MDC_KEY_TRACE_ID, traceId);
        response.setHeader(HEADER_NAME_TRACE_ID, traceId);

        try {
            chain.doFilter(request, response);
        } finally {
            // 5. 必须清理 MDC，避免线程复用污染
            MDC.remove(MDC_KEY_TRACE_ID);
        }
    }

}
