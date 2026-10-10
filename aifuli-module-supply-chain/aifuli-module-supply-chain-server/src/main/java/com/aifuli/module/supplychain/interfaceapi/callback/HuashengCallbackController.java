package com.aifuli.module.supplychain.interfaceapi.callback;

import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengCallbackApplicationService;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengCallbackConflictException;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengCallbackNotFoundException;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengCallbackValidationException;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengLockBusyException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 华昇供应商公网回调薄控制器。
 *
 * <p>职责：
 * <ul>
 *   <li>接收供应商推送的履约终态回调（{@code POST /callbacks/suppliers/huasheng/{connectorVersionId}}）；</li>
 *   <li>免登录 Token 放行，严格由应用服务与解析器执行供应商 Appid 与 RSA 验签认证；</li>
 *   <li>薄控制器架构：仅依赖 {@link HuashengCallbackApplicationService}，严禁依赖 DAL Mapper 或 HTTP 外呼；</li>
 *   <li>HTTP 状态码严格遵循设计契约：仅处理成功或同内容幂等返回 200 {@code "success"}，
 *       校验失败返回 400，版本/原单未找到返回 404，nonce 冲突返回 409，进程锁忙返回 503，内部异常返回 500，
 *       绝不包装为业务 200 JSON。</li>
 * </ul>
 *
 * <p>设计锚点：docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#81-冻结身份认证
 * 及 docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#82-持久防重与收敛。
 */
@Slf4j
@RestController
@RequestMapping("/callbacks/suppliers/huasheng")
public class HuashengCallbackController {

    private final HuashengCallbackApplicationService applicationService;

    public HuashengCallbackController(HuashengCallbackApplicationService applicationService) {
        this.applicationService = Objects.requireNonNull(applicationService, "applicationService must not be null");
    }

    @PostMapping(value = "/{connectorVersionId}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> handleCallback(
            @PathVariable("connectorVersionId") Long connectorVersionId,
            @RequestHeader(value = "Appid", required = false) String appIdHeader,
            @RequestHeader(value = "X-Sign", required = false) String xSignHeader,
            @RequestHeader(value = "trace-id", required = false) String traceId,
            HttpServletRequest request) {
        try {
            InputStream inputStream = request.getInputStream();
            applicationService.handleCallback(connectorVersionId, appIdHeader, xSignHeader, inputStream, traceId);
            return ResponseEntity.ok("success");
        } catch (HuashengCallbackValidationException e) {
            log.warn("[华昇回调][校验失败] connectorVersionId={}", connectorVersionId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Invalid callback");
        } catch (HuashengCallbackNotFoundException e) {
            log.warn("[华昇回调][未找到资源] connectorVersionId={}", connectorVersionId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Callback resource not found");
        } catch (HuashengCallbackConflictException e) {
            log.warn("[华昇回调][冲突拒绝] connectorVersionId={}", connectorVersionId);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Callback conflict");
        } catch (HuashengLockBusyException e) {
            log.warn("[华昇回调][进程锁忙] connectorVersionId={}", connectorVersionId);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Callback busy");
        } catch (Exception e) {
            log.error("[华昇回调][内部异常] connectorVersionId={}, errorType={}", connectorVersionId, safeCausalChain(e));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Internal Server Error");
        }
    }

    private static String safeCausalChain(Throwable e) {
        if (e == null) return "Unknown";
        List<String> types = new ArrayList<>();
        Throwable curr = e;
        while (curr != null && types.size() < 10) {
            types.add(curr.getClass().getSimpleName());
            curr = curr.getCause();
        }
        return String.join(" -> ", types);
    }

    @ExceptionHandler(HuashengCallbackValidationException.class)
    public ResponseEntity<String> handleValidationException(HuashengCallbackValidationException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.TEXT_PLAIN)
                .body("Invalid callback");
    }

    @ExceptionHandler(HuashengCallbackNotFoundException.class)
    public ResponseEntity<String> handleNotFoundException(HuashengCallbackNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.TEXT_PLAIN)
                .body("Callback resource not found");
    }

    @ExceptionHandler(HuashengCallbackConflictException.class)
    public ResponseEntity<String> handleConflictException(HuashengCallbackConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.TEXT_PLAIN)
                .body("Callback conflict");
    }

    @ExceptionHandler(HuashengLockBusyException.class)
    public ResponseEntity<String> handleLockBusyException(HuashengLockBusyException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.TEXT_PLAIN)
                .body("Callback busy");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<String> handleTypeMismatchException(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.TEXT_PLAIN)
                .body("Invalid parameter: " + e.getName());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleGenericException(Exception e) {
        log.error("[华昇回调][内部异常] errorType={}", safeCausalChain(e));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.TEXT_PLAIN)
                .body("Internal Server Error");
    }
}
