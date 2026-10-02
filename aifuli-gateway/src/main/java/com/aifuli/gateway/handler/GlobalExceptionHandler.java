package com.aifuli.gateway.handler;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.gateway.filter.logging.AccessLogSanitizeUtils;
import com.aifuli.gateway.util.WebFrameworkUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static com.aifuli.framework.common.exception.enums.GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR;

/**
 * Gateway 的全局异常处理器，将 Exception 翻译成 CommonResult + 对应的异常编号
 *
 * 在功能上，和 aifuli-spring-boot-starter-web 的 GlobalExceptionHandler 类是一致的
 *
 * @author 芋道源码
 */
@Component
@Order(-1) // 保证优先级高于默认的 Spring Cloud Gateway 的 ErrorWebExceptionHandler 实现
@Slf4j
public class GlobalExceptionHandler implements ErrorWebExceptionHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        // 已经 commit，则直接返回异常
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(ex);
        }

        String rawPath = exchange.getRequest().getURI().getRawPath();
        boolean standardOrder = AccessLogSanitizeUtils.isStandardOrdersPath(rawPath);
        if (standardOrder) {
            response.getHeaders().set("Cache-Control", "no-store");
        }

        // 转换成 CommonResult
        CommonResult<?> result;
        if (ex instanceof ResponseStatusException) {
            result = responseStatusExceptionHandler(exchange, (ResponseStatusException) ex);
        } else {
            result = defaultExceptionHandler(exchange, ex);
        }

        // 返回给前端
        return WebFrameworkUtils.writeJSON(exchange, result);
    }

    /**
     * 处理 Spring Cloud Gateway 默认抛出的 ResponseStatusException 异常
     */
    private CommonResult<?> responseStatusExceptionHandler(ServerWebExchange exchange,
                                                           ResponseStatusException ex) {
        ServerHttpRequest request = exchange.getRequest();
        String rawPath = request.getURI().getRawPath();
        if (AccessLogSanitizeUtils.isStandardOrdersPath(rawPath)) {
            log.error("[responseStatusExceptionHandler][safeUri({}/{}) status({}) 发生异常]",
                    AccessLogSanitizeUtils.safeStandardOrderUrl(rawPath),
                    request.getMethod(), ex.getStatusCode());
            return CommonResult.error(ex.getStatusCode().value(), "请求失败");
        }
        log.error("[responseStatusExceptionHandler][uri({}/{}) 发生异常]", request.getURI(), request.getMethod(), ex);
        return CommonResult.error(ex.getStatusCode().value(), ex.getReason());
    }

    /**
     * 处理系统异常，兜底处理所有的一切
     */
    @ExceptionHandler(value = Exception.class)
    public CommonResult<?> defaultExceptionHandler(ServerWebExchange exchange,
                                                   Throwable ex) {
        ServerHttpRequest request = exchange.getRequest();
        String rawPath = request.getURI().getRawPath();
        if (AccessLogSanitizeUtils.isStandardOrdersPath(rawPath)) {
            log.error("[defaultExceptionHandler][safeUri({}/{}) 发生异常: {}]",
                    AccessLogSanitizeUtils.safeStandardOrderUrl(rawPath),
                    request.getMethod(), ex.getClass().getSimpleName());
            return CommonResult.error(INTERNAL_SERVER_ERROR.getCode(), INTERNAL_SERVER_ERROR.getMsg());
        }
        log.error("[defaultExceptionHandler][uri({}/{}) 发生异常]", request.getURI(), request.getMethod(), ex);
        // 返回 ERROR CommonResult
        return CommonResult.error(INTERNAL_SERVER_ERROR.getCode(), INTERNAL_SERVER_ERROR.getMsg());
    }

}
