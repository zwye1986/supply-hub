package com.aifuli.module.supplychain.interfaceapi.callback;

import com.aifuli.module.supplychain.border.fulfillment.application.singlestep.SingleStepCallbackApplicationService;
import com.aifuli.module.supplychain.border.fulfillment.domain.SingleStepCallbackException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import static com.aifuli.module.supplychain.border.fulfillment.domain.SingleStepCallbackException.Code.*;

/**
 * 仁跃回调薄HTTP边界，成功纯文本ACK；处理失败按用户要求200+固定错误码/安全信息。
 * 不合并query/form参数，不捕获后假ACK，不输出原始异常文本。
 * 设计：docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#513-临时本地认证与http闭环合同2026-10-18用户批准。
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class SingleStepSupplierCallbackController {
    private static final int MAX_CAUSAL_NODES = 32;
    private static final int MAX_STACK_FRAMES = 200;
    private final SingleStepCallbackApplicationService application;
    @PostMapping("/callbacks/suppliers/renyue/{connectorVersionId}")
    public ResponseEntity<?> callback(@PathVariable("connectorVersionId") String version, HttpServletRequest request) {
        try {
            long versionId;
            try {
                if (!version.matches("[0-9]{1,19}")) throw new IllegalArgumentException();
                versionId = Long.parseLong(version);
                var type = MediaType.parseMediaType(request.getContentType());
                if (versionId <= 0 || !MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(type)
                        || !type.isConcrete()
                        || type.getCharset() != null && !StandardCharsets.UTF_8.equals(type.getCharset())
                        || request.getQueryString() != null) throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) { throw new SingleStepCallbackException(INVALID_CALLBACK); }
            // 复用入口已建立的平台编号；供应商未传Header时也不能把null写入审计。设计同§5.1.3及日志规范§5.1。
            String traceId = java.util.Objects.requireNonNullElse(org.slf4j.MDC.get(
                    com.aifuli.framework.common.util.monitor.TracerUtils.MDC_KEY_TRACE_ID), "");
            application.handleRenyue(versionId, request.getInputStream(), traceId);
            // 无外层事务；应用入口的两个独立事务均已返回，提交失败会进入下方错误JSON分支。
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).cacheControl(org.springframework.http.CacheControl.noStore()).body("success");
        } catch (SingleStepCallbackException rejected) {
            log.warn("[renyueCallbackRejected][仁跃回调未接管] errorCode={}", rejected.code());
            return failure(rejected.code());
        } catch (com.aifuli.framework.common.exception.ServiceException rejected) {
            log.warn("[renyueCallbackRejected][仁跃回调业务拒绝] errorCode={}, businessCode={}", CALLBACK_PROCESSING_FAILED, rejected.getCode());
            return failure(CALLBACK_PROCESSING_FAILED);
        } catch (Exception failure) {
            log.error("[renyueCallbackFailed][仁跃回调处理失败] errorCode={}", CALLBACK_PROCESSING_FAILED, safeCause(failure));
            return failure(CALLBACK_PROCESSING_FAILED);
        }
    }
    private static ResponseEntity<Failure> failure(SingleStepCallbackException.Code code) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new Failure(code.name(), code.message()));
    }
    public record Failure(String code, String message) { }

    /**
     * 终结边界只复制类型/栈帧，保留cause与suppressed但不读取原消息；身份去重防环并限制图/帧总量。
     * 设计：docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#513-临时本地认证与http闭环合同2026-10-18用户批准；日志规范§2.3。
     */
    private static RuntimeException safeCause(Throwable failure) {
        return safeCause(failure, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()), new int[]{MAX_STACK_FRAMES});
    }

    private static RuntimeException safeCause(Throwable failure, java.util.Set<Throwable> seen, int[] remainingFrames) {
        if (failure == null || seen.size() >= MAX_CAUSAL_NODES || !seen.add(failure)) return null;
        var safe = new RuntimeException(failure.getClass().getName());
        var frames = failure.getStackTrace();
        int count = Math.min(frames.length, remainingFrames[0]);
        safe.setStackTrace(java.util.Arrays.copyOf(frames, count));
        remainingFrames[0] -= count;
        safe.initCause(safeCause(failure.getCause(), seen, remainingFrames));
        for (var suppressed : failure.getSuppressed()) {
            if (seen.size() >= MAX_CAUSAL_NODES) break;
            var copy = safeCause(suppressed, seen, remainingFrames);
            if (copy != null) safe.addSuppressed(copy);
        }
        return safe;
    }
}
