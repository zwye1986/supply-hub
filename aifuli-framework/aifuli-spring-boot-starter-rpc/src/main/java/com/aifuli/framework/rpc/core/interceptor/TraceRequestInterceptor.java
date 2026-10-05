package com.aifuli.framework.rpc.core.interceptor;

import cn.hutool.core.util.StrUtil;
import com.aifuli.framework.common.util.monitor.TracerUtils;
import feign.RequestInterceptor;
import feign.RequestTemplate;

/**
 * 链路追踪的 {@link RequestInterceptor} 实现类：
 * Feign 发起远程调用时，将当前请求的 traceId 写入请求头中透传给下游微服务。
 * TracerUtils.getTraceId() 内部已兜底读取 MDC，无需额外重复查询。
 *
 * 设计见 {@code docs/superpowers/specs/2026-10-04-elk-logging-integration-design.md#42-feign-跨微服务链路透传}
 *
 * @author 芋道源码
 */
public class TraceRequestInterceptor implements RequestInterceptor {

    /**
     * Header 名 - 链路追踪编号
     */
    public static final String HEADER_NAME_TRACE_ID = "trace-id";

    @Override
    public void apply(RequestTemplate template) {
        String traceId = TracerUtils.getTraceId();
        if (StrUtil.isNotBlank(traceId)) {
            template.header(HEADER_NAME_TRACE_ID, traceId);
        }
    }

}
