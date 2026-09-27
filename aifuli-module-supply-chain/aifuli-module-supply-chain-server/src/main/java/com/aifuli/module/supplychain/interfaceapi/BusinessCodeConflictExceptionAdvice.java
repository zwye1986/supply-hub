package com.aifuli.module.supplychain.interfaceapi;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.web.core.handler.GlobalExceptionHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUSINESS_CODE_LEGACY_PARENT_OCCUPIED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUSINESS_CODE_OCCUPIED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUSINESS_CODE_PERMANENT_EXISTS;

/**
 * 供应链 HTTP 边界对高频业务编码冲突按错误码计数和采样告警。
 * 非编号冲突仍交给既有全局异常处理器，保持其它业务响应与日志语义。
 * 设计见 {@code docs/superpowers/specs/2026-09-27-supply-chain-business-code-design.md#5-存储迁移与并发}。
 */
@Slf4j
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.aifuli.module.supplychain.interfaceapi")
public class BusinessCodeConflictExceptionAdvice {

    private static final long LOG_EVERY = 100L;
    private static final Set<Integer> CONFLICT_CODES = Set.of(
            BUSINESS_CODE_OCCUPIED.getCode(), BUSINESS_CODE_PERMANENT_EXISTS.getCode(),
            BUSINESS_CODE_LEGACY_PARENT_OCCUPIED.getCode());

    private final GlobalExceptionHandler fallback;
    private final ConcurrentHashMap<Integer, AtomicLong> counts = new ConcurrentHashMap<>();

    /** 仅对编号冲突采样；其它业务异常复用全局处理器以保留原响应和日志。 */
    @ExceptionHandler(ServiceException.class)
    public CommonResult<?> handle(ServiceException error) {
        int code = error.getCode();
        if (!CONFLICT_CODES.contains(code)) {
            return fallback.serviceExceptionHandler(error);
        }
        long count = counts.computeIfAbsent(code, ignored -> new AtomicLong()).incrementAndGet();
        if (count == 1 || count % LOG_EVERY == 0) {
            log.warn("业务编码冲突 code={} count={} sampleMessage={}", code, count, error.getMessage());
        }
        return CommonResult.error(code, error.getMessage());
    }
}
