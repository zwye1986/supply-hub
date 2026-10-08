package com.aifuli.framework.common.util.monitor;

import cn.hutool.core.util.StrUtil;
import org.apache.skywalking.apm.toolkit.trace.TraceContext;
import org.slf4j.MDC;

/**
 * 链路追踪工具类
 *
 * 考虑到每个 starter 都需要用到该工具类，所以放到 common 模块下的 util 包下
 *
 * 设计见 {@code docs/superpowers/specs/2026-10-04-elk-logging-integration-design.md#41-tracefilter-改造与-mdc-注入规范}
 *
 * @author 芋道源码
 */
public class TracerUtils {

    /**
     * MDC 链路追踪标识 Key
     */
    public static final String MDC_KEY_TRACE_ID = "traceId";

    /**
     * 私有化构造方法
     */
    private TracerUtils() {
    }

    /**
     * 获得链路追踪编号：
     * 1. 优先从 SkyWalking 上下文中获取 TraceId；
     * 2. 若 SkyWalking TraceId 为空或为未激活标记（[TID: N/A]），则尝试从 SLF4J MDC 中获取；
     * 3. 均未命中时返回空字符串。
     *
     * @return 链路追踪编号
     */
    public static String getTraceId() {
        String traceId = TraceContext.traceId();
        if (StrUtil.isNotBlank(traceId) && !"[TID: N/A]".equalsIgnoreCase(traceId)) {
            return traceId;
        }
        String mdcTraceId = MDC.get(MDC_KEY_TRACE_ID);
        if (StrUtil.isNotBlank(mdcTraceId)) {
            return mdcTraceId;
        }
        return StrUtil.EMPTY;
    }

}
