package com.aifuli.framework.web.core.handler;

import cn.hutool.core.collection.CollUtil;
import com.aifuli.framework.common.util.http.ApiAccessLogPathUtils;
import com.aifuli.framework.apilog.core.util.SensitiveExceptionLogUtils;
import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.aifuli.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import com.aifuli.framework.common.biz.infra.logger.dto.ApiErrorLogCreateReqDTO;
import com.aifuli.framework.common.exception.ErrorCode;
import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.common.util.collection.SetUtils;
import com.aifuli.framework.common.util.json.JsonUtils;
import com.aifuli.framework.common.util.monitor.TracerUtils;
import com.aifuli.framework.common.util.servlet.ServletUtils;
import com.aifuli.framework.web.core.util.WebFrameworkUtils;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.google.common.util.concurrent.UncheckedExecutionException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ValidationException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.util.Assert;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.aifuli.framework.common.exception.enums.GlobalErrorCodeConstants.*;

/**
 * 全局异常处理器，将 Exception 翻译成 CommonResult + 对应的异常编号
 *
 * @author 芋道源码
 */
@RestControllerAdvice
@AllArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 忽略的 ServiceException 错误提示，避免打印过多 logger
     */
    public static final Set<String> IGNORE_ERROR_MESSAGES = SetUtils.asSet("无效的刷新令牌");

    /**
     * 限频错误日志存储失败独立计数；与访问日志API失败计数分开，首次及每100次保留安全因果位置。
     * 设计见 {@code docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#7-管理端契约与权限}。
     */
    private static final long SENSITIVE_ERROR_LOG_FAILURE_SAMPLE_INTERVAL = 100L;
    private static final java.util.concurrent.atomic.AtomicLong SENSITIVE_ERROR_LOG_FAILURES = new java.util.concurrent.atomic.AtomicLong();

    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    private final String applicationName;

    private final ApiErrorLogCommonApi apiErrorLogApi;

    /**
     * 处理所有异常，主要是提供给 Filter 使用
     * 因为 Filter 不走 SpringMVC 的流程，但是我们又需要兜底处理异常，所以这里提供一个全量的异常处理过程，保持逻辑统一。
     *
     * @param request 请求
     * @param ex 异常
     * @return 通用返回
     */
    public CommonResult<?> allExceptionHandler(HttpServletRequest request, Throwable ex) {
        if (isParameterFailure(ex)) {
            CommonResult<?> safeParameter = safeParameterFailureIfSensitive(request, ex);
            if (safeParameter != null) { return safeParameter; }
        }
        if (ex instanceof MissingServletRequestParameterException) {
            return missingServletRequestParameterExceptionHandler((MissingServletRequestParameterException) ex);
        }
        if (ex instanceof MethodArgumentTypeMismatchException) {
            return methodArgumentTypeMismatchExceptionHandler((MethodArgumentTypeMismatchException) ex);
        }
        if (ex instanceof MethodArgumentNotValidException) {
            return methodArgumentNotValidExceptionExceptionHandler((MethodArgumentNotValidException) ex);
        }
        if (ex instanceof BindException) {
            return bindExceptionHandler((BindException) ex);
        }
        if (ex instanceof ConstraintViolationException) {
            return constraintViolationExceptionHandler((ConstraintViolationException) ex);
        }
        if (ex instanceof ValidationException) {
            return validationException((ValidationException) ex);
        }
        if (ex instanceof MaxUploadSizeExceededException) {
            return maxUploadSizeExceededExceptionHandler((MaxUploadSizeExceededException) ex);
        }
        if (ex instanceof NoHandlerFoundException) {
            return noHandlerFoundExceptionHandler((NoHandlerFoundException) ex);
        }
        if (ex instanceof NoResourceFoundException) {
            return noResourceFoundExceptionHandler(request, (NoResourceFoundException) ex);
        }
        if (ex instanceof HttpRequestMethodNotSupportedException) {
            return httpRequestMethodNotSupportedExceptionHandler((HttpRequestMethodNotSupportedException) ex);
        }
        if (ex instanceof HttpMediaTypeNotSupportedException) {
            return httpMediaTypeNotSupportedExceptionHandler((HttpMediaTypeNotSupportedException) ex);
        }
        if (ex instanceof ServiceException) {
            return serviceExceptionHandler((ServiceException) ex);
        }
        if (ex instanceof AccessDeniedException) {
            return accessDeniedExceptionHandler(request, (AccessDeniedException) ex);
        }
        return defaultExceptionHandler(request, ex);
    }

    /**
     * 处理 SpringMVC 请求参数缺失
     *
     * 例如说，接口上设置了 @RequestParam("xx") 参数，结果并未传递 xx 参数
     */
    @ExceptionHandler(value = MissingServletRequestParameterException.class)
    public CommonResult<?> missingServletRequestParameterExceptionHandler(MissingServletRequestParameterException ex) {
        HttpServletRequest request = ServletUtils.getRequest();
        CommonResult<?> safe = safeParameterFailureIfSensitive(request, ex);
        if (safe != null) { return safe; }
        log.warn("[missingServletRequestParameterExceptionHandler]", ex);
        return CommonResult.error(BAD_REQUEST.getCode(), String.format("请求参数缺失:%s", ex.getParameterName()));
    }

    /**
     * 处理 SpringMVC 请求参数类型错误
     *
     * 例如说，接口上设置了 @RequestParam("xx") 参数为 Integer，结果传递 xx 参数类型为 String
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public CommonResult<?> methodArgumentTypeMismatchExceptionHandler(MethodArgumentTypeMismatchException ex) {
        HttpServletRequest request = ServletUtils.getRequest();
        CommonResult<?> safe = safeParameterFailureIfSensitive(request, ex);
        if (safe != null) { return safe; }
        log.warn("[methodArgumentTypeMismatchExceptionHandler]", ex);
        return CommonResult.error(BAD_REQUEST.getCode(), String.format("请求参数类型错误:%s", ex.getMessage()));
    }

    /**
     * 处理 SpringMVC 参数校验不正确
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public CommonResult<?> methodArgumentNotValidExceptionExceptionHandler(MethodArgumentNotValidException ex) {
        HttpServletRequest request = ServletUtils.getRequest();
        CommonResult<?> safe = safeParameterFailureIfSensitive(request, ex);
        if (safe != null) { return safe; }
        log.warn("[methodArgumentNotValidExceptionExceptionHandler]", ex);
        // 获取 errorMessage
        String errorMessage = null;
        FieldError fieldError = ex.getBindingResult().getFieldError();
        if (fieldError == null) {
            // 组合校验，参考自 https://t.zsxq.com/3HVTx
            List<ObjectError> allErrors = ex.getBindingResult().getAllErrors();
            if (CollUtil.isNotEmpty(allErrors)) {
                errorMessage = allErrors.get(0).getDefaultMessage();
            }
        } else {
            // 拼接字段名与校验说明，便于排查（如 "fulfillmentType 不能为空"）
            errorMessage = fieldError.getField() + " " + fieldError.getDefaultMessage();
        }
        // 转换 CommonResult
        if (StrUtil.isEmpty(errorMessage)) {
            return CommonResult.error(BAD_REQUEST);
        }
        return CommonResult.error(BAD_REQUEST.getCode(), String.format("请求参数不正确:%s", errorMessage));
    }

    /**
     * 处理 SpringMVC 参数绑定不正确，本质上也是通过 Validator 校验
     */
    @ExceptionHandler(BindException.class)
    public CommonResult<?> bindExceptionHandler(BindException ex) {
        HttpServletRequest request = ServletUtils.getRequest();
        CommonResult<?> safe = safeParameterFailureIfSensitive(request, ex);
        if (safe != null) { return safe; }
        log.warn("[handleBindException]", ex);
        FieldError fieldError = ex.getFieldError();
        assert fieldError != null; // 断言，避免告警
        return CommonResult.error(BAD_REQUEST.getCode(), String.format("请求参数不正确:%s %s", fieldError.getField(), fieldError.getDefaultMessage()));
    }

    /**
     * 处理 SpringMVC 请求参数类型错误
     *
     * 例如说，接口上设置了 @RequestBody 实体中 xx 属性类型为 Integer，结果传递 xx 参数类型为 String
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @SuppressWarnings("PatternVariableCanBeUsed")
    public CommonResult<?> methodArgumentTypeInvalidFormatExceptionHandler(HttpMessageNotReadableException ex) {
        HttpServletRequest request = ServletUtils.getRequest();
        CommonResult<?> safe = safeParameterFailureIfSensitive(request, ex);
        if (safe != null) { return safe; }
        log.warn("[methodArgumentTypeInvalidFormatExceptionHandler]", ex);
        if (ex.getCause() instanceof InvalidFormatException) {
            InvalidFormatException invalidFormatException = (InvalidFormatException) ex.getCause();
            return CommonResult.error(BAD_REQUEST.getCode(), String.format("请求参数类型错误:%s", invalidFormatException.getValue()));
        }
        if (StrUtil.startWith(ex.getMessage(), "Required request body is missing")) {
            return CommonResult.error(BAD_REQUEST.getCode(), "请求参数类型错误: request body 缺失");
        }
        return defaultExceptionHandler(ServletUtils.getRequest(), ex);
    }

    /**
     * 处理 Validator 校验不通过产生的异常
     */
    @ExceptionHandler(value = ConstraintViolationException.class)
    public CommonResult<?> constraintViolationExceptionHandler(ConstraintViolationException ex) {
        HttpServletRequest request = ServletUtils.getRequest();
        CommonResult<?> safe = safeParameterFailureIfSensitive(request, ex);
        if (safe != null) { return safe; }
        log.warn("[constraintViolationExceptionHandler]", ex);
        ConstraintViolation<?> constraintViolation = ex.getConstraintViolations().iterator().next();
        return CommonResult.error(BAD_REQUEST.getCode(), String.format("请求参数不正确:%s", constraintViolation.getMessage()));
    }

    /**
     * 处理 Dubbo Consumer 本地参数校验时，抛出的 ValidationException 异常
     */
    @ExceptionHandler(value = ValidationException.class)
    public CommonResult<?> validationException(ValidationException ex) {
        HttpServletRequest request = ServletUtils.getRequest();
        CommonResult<?> safe = safeParameterFailureIfSensitive(request, ex);
        if (safe != null) { return safe; }
        log.warn("[constraintViolationExceptionHandler]", ex);
        // 无法拼接明细的错误信息，因为 Dubbo Consumer 抛出 ValidationException 异常时，是直接的字符串信息，且人类不可读
        return CommonResult.error(BAD_REQUEST);
    }

    /**
     * 处理上传文件过大异常
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public CommonResult<?> maxUploadSizeExceededExceptionHandler(MaxUploadSizeExceededException ex) {
        return CommonResult.error(BAD_REQUEST.getCode(), "上传文件过大，请调整后重试");
    }

    /**
     * 处理 SpringMVC 请求地址不存在
     *
     * 注意，它需要设置如下两个配置项：
     * 1. spring.mvc.throw-exception-if-no-handler-found 为 true
     * 2. spring.mvc.static-path-pattern 为 /statics/**
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public CommonResult<?> noHandlerFoundExceptionHandler(NoHandlerFoundException ex) {
        HttpServletRequest request = ServletUtils.getRequest();
        CommonResult<?> safe = safeErrorResponseIfSensitive(request, ex, NOT_FOUND);
        if (safe != null) { return safe; }
        log.warn("[noHandlerFoundExceptionHandler]", ex);
        return CommonResult.error(NOT_FOUND.getCode(), String.format("请求地址不存在:%s", ex.getRequestURL()));
    }

    /**
     * 处理 SpringMVC 请求地址不存在
     */
    @ExceptionHandler(NoResourceFoundException.class)
    private CommonResult<?> noResourceFoundExceptionHandler(HttpServletRequest req, NoResourceFoundException ex) {
        CommonResult<?> safe = safeErrorResponseIfSensitive(req, ex, NOT_FOUND);
        if (safe != null) { return safe; }
        log.warn("[noResourceFoundExceptionHandler]", ex);
        return CommonResult.error(NOT_FOUND.getCode(), String.format("请求地址不存在:%s", ex.getResourcePath()));
    }

    /**
     * 处理 SpringMVC 请求方法不正确
     *
     * 例如说，A 接口的方法为 GET 方式，结果请求方法为 POST 方式，导致不匹配
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public CommonResult<?> httpRequestMethodNotSupportedExceptionHandler(HttpRequestMethodNotSupportedException ex) {
        HttpServletRequest request = ServletUtils.getRequest();
        CommonResult<?> safe = safeErrorResponseIfSensitive(request, ex, METHOD_NOT_ALLOWED);
        if (safe != null) { return safe; }
        log.warn("[httpRequestMethodNotSupportedExceptionHandler]", ex);
        return CommonResult.error(METHOD_NOT_ALLOWED.getCode(), String.format("请求方法不正确:%s", ex.getMessage()));
    }

    /**
     * 处理 SpringMVC 请求的 Content-Type 不正确
     *
     * 例如说，A 接口的 Content-Type 为 application/json，结果请求的 Content-Type 为 application/octet-stream，导致不匹配
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public CommonResult<?> httpMediaTypeNotSupportedExceptionHandler(HttpMediaTypeNotSupportedException ex) {
        HttpServletRequest request = ServletUtils.getRequest();
        CommonResult<?> safe = safeErrorResponseIfSensitive(request, ex, BAD_REQUEST);
        if (safe != null) { return safe; }
        log.warn("[httpMediaTypeNotSupportedExceptionHandler]", ex);
        return CommonResult.error(BAD_REQUEST.getCode(), String.format("请求类型不正确:%s", ex.getMessage()));
    }

    /**
     * 处理 Spring Security 权限不足的异常
     *
     * 来源是，使用 @PreAuthorize 注解，AOP 进行权限拦截
     */
    @ExceptionHandler(value = AccessDeniedException.class)
    public CommonResult<?> accessDeniedExceptionHandler(HttpServletRequest req, AccessDeniedException ex) {
        CommonResult<?> safe = safeErrorResponseIfSensitive(req, ex, FORBIDDEN);
        if (safe != null) { return safe; }
        log.warn("[accessDeniedExceptionHandler][userId({}) 无法访问 url({})]", WebFrameworkUtils.getLoginUserId(req),
                req.getRequestURL(), ex);
        return CommonResult.error(FORBIDDEN);
    }

    /**
     * 处理 Guava UncheckedExecutionException
     *
     * 例如说，缓存加载报错，可见 <a href="https://t.zsxq.com/UszdH">https://t.zsxq.com/UszdH</a>
     */
    @ExceptionHandler(value = UncheckedExecutionException.class)
    public CommonResult<?> uncheckedExecutionExceptionHandler(HttpServletRequest req, UncheckedExecutionException ex) {
        return allExceptionHandler(req, ex.getCause());
    }

    /**
     * 处理业务异常 ServiceException
     *
     * 例如说，商品库存不足，用户手机号已存在。
     */
    @ExceptionHandler(value = ServiceException.class)
    public CommonResult<?> serviceExceptionHandler(ServiceException ex) {
        HttpServletRequest request = ServletUtils.getRequest();
        CommonResult<?> safeBusiness = safeBusinessRejectionIfSensitive(request, ex);
        if (safeBusiness != null) { return safeBusiness; }
        // 业务拒绝由 HTTP 终结边界记录一次单行 WARN，不附异常对象或调用栈；
        // 忽略文案与上方敏感路径渲染规则保持不变。设计见
        // docs/superpowers/specs/2026-10-04-logging-standardization-spec.md#23-异常日志分级与防重复打印规范职责单一原则。
        if (!IGNORE_ERROR_MESSAGES.contains(ex.getMessage())) {
            log.warn("[serviceExceptionHandler][业务请求被拒绝] code={}, message={}",
                    ex.getCode(), ex.getMessage());
        }
        return CommonResult.error(ex.getCode(), ex.getMessage());
    }

    /**
     * 处理系统异常，兜底处理所有的一切
     */
    @ExceptionHandler(value = Exception.class)
    public CommonResult<?> defaultExceptionHandler(HttpServletRequest req, Throwable ex) {
        SensitivePathKind kind = classifySensitivePath(req);
        if (kind != SensitivePathKind.NONE) {
            return handleSensitiveSystemException(req, ex, kind);
        }
        // 特殊：如果是 ServiceException 的异常，则直接返回
        // 例如说：https://gitee.com/zhijiantianya/aifuli-cloud/issues/ICSSRM、https://gitee.com/zhijiantianya/aifuli-cloud/issues/ICT6FM
        if (ex.getCause() != null && ex.getCause() instanceof ServiceException) {
            return serviceExceptionHandler((ServiceException) ex.getCause());
        }

        // 情况一：处理表不存在的异常
        CommonResult<?> tableNotExistsResult = handleTableNotExists(ex);
        if (tableNotExistsResult != null) {
            return tableNotExistsResult;
        }

        // 情况二：处理异常
        log.error("[defaultExceptionHandler]", ex);
        // 插入异常日志
        createExceptionLog(req, ex);
        // 返回 ERROR CommonResult
        return CommonResult.error(INTERNAL_SERVER_ERROR.getCode(), INTERNAL_SERVER_ERROR.getMsg());
    }

    /**
     * 敏感路径系统异常统一收敛骨架：标准订单与限频管理共用同一段"参数失败/ServiceException/系统异常"分派，
     * 仅日志标签与参数失败文案按 kind 区分，消除 defaultExceptionHandler 内的同构分支重复。
     * 设计见 {@code docs/superpowers/specs/2026-10-02-standard-project-http-transport-design.md#4-共享web保护与影响范围}
     * 与 {@code docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#7-管理端契约与权限}。
     */
    private CommonResult<?> handleSensitiveSystemException(HttpServletRequest req, Throwable ex, SensitivePathKind kind) {
        boolean standardOrder = kind == SensitivePathKind.STANDARD_ORDER;
        boolean supplierCallback = kind == SensitivePathKind.SUPPLIER_CALLBACK;
        if (standardOrder || kind == SensitivePathKind.SUPPLIER_CREDENTIAL || supplierCallback) { ensureNoStore(); }
        if (isParameterFailure(ex)) {
            if (kind == SensitivePathKind.SUPPLIER_CREDENTIAL) { return safeCredentialParameterFailure(ex); }
            if (supplierCallback) { return safeSupplierCallbackParameterFailure(ex); }
            return standardOrder ? safeStandardOrderParameterFailure(ex) : safeParameterFailure(ex);
        }
        if (ex instanceof ServiceException service) {
            return CommonResult.error(service.getCode(), service.getMessage());
        }
        if (ex.getCause() instanceof ServiceException service) {
            return CommonResult.error(service.getCode(), service.getMessage());
        }
        String label = switch (kind) {
            case STANDARD_ORDER -> "[标准订单系统异常]";
            case SUPPLIER_CREDENTIAL -> "[供应商凭据系统异常]";
            case SUPPLIER_CALLBACK -> "[供应商回调系统异常]";
            default -> "[限频管理系统异常]";
        };
        log.error("{} types={}, traceId={}", label, SensitiveExceptionLogUtils.types(ex), TracerUtils.getTraceId());
        createExceptionLog(req, ex);
        return CommonResult.error(INTERNAL_SERVER_ERROR);
    }

    private void createExceptionLog(HttpServletRequest req, Throwable e) {
        // 插入错误日志
        ApiErrorLogCreateReqDTO errorLog = new ApiErrorLogCreateReqDTO();
        try {
            // 初始化 errorLog
            buildExceptionLog(errorLog, req, e);
            // 执行插入 errorLog
            apiErrorLogApi.createApiErrorLogAsync(errorLog);
        } catch (Throwable th) {
            if (classifySensitivePath(req) != SensitivePathKind.NONE) {
                long failures = SENSITIVE_ERROR_LOG_FAILURES.incrementAndGet();
                if (failures == 1 || failures % SENSITIVE_ERROR_LOG_FAILURE_SAMPLE_INTERVAL == 0) {
                    log.error("[敏感路径异常日志存储失败] failureCount={}, types={}, traceId={}, safeStack={}", failures,
                            SensitiveExceptionLogUtils.types(th), TracerUtils.getTraceId(), SensitiveExceptionLogUtils.stack(th));
                }
                // 平台错误日志兜底不覆盖原系统异常500响应；采样仍保留安全cause与位置，禁止输出原正文或DTO。
                return;
            }
            log.error("[createExceptionLog][url({}) log({}) 发生异常]", req.getRequestURI(),  JsonUtils.toJsonString(errorLog), th);
        }
    }

    private void buildExceptionLog(ApiErrorLogCreateReqDTO errorLog, HttpServletRequest request, Throwable e) {
        // 处理用户信息
        errorLog.setUserId(WebFrameworkUtils.getLoginUserId(request));
        errorLog.setUserType(WebFrameworkUtils.getLoginUserType(request));
        // 设置异常字段
        errorLog.setExceptionName(e.getClass().getName());
        SensitivePathKind kind = classifySensitivePath(request);
        boolean frequency = kind == SensitivePathKind.RECHARGE_FREQUENCY;
        boolean standardOrder = kind == SensitivePathKind.STANDARD_ORDER;
        boolean sensitive = kind != SensitivePathKind.NONE;
        errorLog.setExceptionMessage(sensitive ? SensitiveExceptionLogUtils.types(e) : ExceptionUtil.getMessage(e));
        errorLog.setExceptionRootCauseMessage(sensitive ? SensitiveExceptionLogUtils.types(e) : ExceptionUtil.getRootCauseMessage(e));
        errorLog.setExceptionStackTrace(sensitive ? SensitiveExceptionLogUtils.stack(e) : ExceptionUtil.stacktraceToString(e));
        StackTraceElement[] stackTraceElements = e.getStackTrace();
        Assert.notEmpty(stackTraceElements, "异常 stackTraceElements 不能为空");
        StackTraceElement stackTraceElement = stackTraceElements[0];
        errorLog.setExceptionClassName(stackTraceElement.getClassName());
        errorLog.setExceptionFileName(stackTraceElement.getFileName());
        errorLog.setExceptionMethodName(stackTraceElement.getMethodName());
        errorLog.setExceptionLineNumber(stackTraceElement.getLineNumber());
        // 设置其它字段
        errorLog.setTraceId(TracerUtils.getTraceId());
        errorLog.setApplicationName(applicationName);
        String requestUrl;
        if (kind == SensitivePathKind.SUPPLIER_CREDENTIAL) {
            requestUrl = "/supply-chain/supplier-config/[credential-protected]";
        } else if (kind == SensitivePathKind.SUPPLIER_CALLBACK) {
            requestUrl = ApiAccessLogPathUtils.safeSupplierCallbackUrl(request.getRequestURI(), request.getContextPath());
        } else if (frequency) {
            requestUrl = "/supply-chain/recharge-frequency/[protected]";
        } else if (standardOrder) {
            requestUrl = ApiAccessLogPathUtils.safeStandardOrderUrl(request.getRequestURI(), request.getContextPath());
        } else {
            requestUrl = request.getRequestURI();
        }
        errorLog.setRequestUrl(requestUrl);
        Map<String, Object> requestParams = sensitive ? Map.of() : MapUtil.<String, Object>builder()
                .put("query", ServletUtils.getParamMap(request))
                .put("body", ServletUtils.getBody(request)).build();
        errorLog.setRequestParams(sensitive ? "{}" : JsonUtils.toJsonString(requestParams));
        errorLog.setRequestMethod(request.getMethod());
        errorLog.setUserAgent(ServletUtils.getUserAgent(request));
        errorLog.setUserIp(ServletUtils.getClientIP(request));
        errorLog.setExceptionTime(LocalDateTime.now());
    }

    /**
     * 敏感路径类别：标准订单开放路径与限频管理路径共用同一套收敛策略骨架（参数错误、HTTP错误、业务拒绝），
     * 普通路径不受影响。分类只认登记的精确路径，避免相似前缀误伤。
     */
    private enum SensitivePathKind {
        STANDARD_ORDER, RECHARGE_FREQUENCY, SUPPLIER_CREDENTIAL, SUPPLIER_CALLBACK, NONE
    }

    /** 只保护登记的标准订单、限频管理与供应商凭据写入路径，八个进程其他路径保持原有错误契约。设计见
     * {@code docs/superpowers/specs/2026-10-02-standard-project-http-transport-design.md#4-共享web保护与影响范围}、
     * {@code docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#7-管理端契约与权限}。 */
    private static SensitivePathKind classifySensitivePath(HttpServletRequest request) {
        if (request == null) {
            return SensitivePathKind.NONE;
        }
        if (ApiAccessLogPathUtils.isStandardOrdersPath(request.getRequestURI(), request.getContextPath())) {
            return SensitivePathKind.STANDARD_ORDER;
        }
        if (ApiAccessLogPathUtils.isRechargeFrequencyAdminPath(request.getRequestURI(), request.getContextPath())) {
            return SensitivePathKind.RECHARGE_FREQUENCY;
        }
        if (ApiAccessLogPathUtils.isSupplierCredentialWritePath(request.getRequestURI(), request.getContextPath())) {
            return SensitivePathKind.SUPPLIER_CREDENTIAL;
        }
        if (ApiAccessLogPathUtils.isSupplierCallbackPath(request.getRequestURI(), request.getContextPath())) {
            return SensitivePathKind.SUPPLIER_CALLBACK;
        }
        return SensitivePathKind.NONE;
    }

    /**
     * 敏感路径参数错误统一收敛策略：标准订单路径补 no-store 并返回固定 400 文案，限频管理路径走白名单字段日志。
     * 返回 null 表示非敏感路径，调用方沿用原有错误契约。
     * 设计见 {@code docs/superpowers/specs/2026-10-02-standard-project-http-transport-design.md#1-controller与严格请求解析}。
     */
    private static CommonResult<?> safeParameterFailureIfSensitive(HttpServletRequest request, Throwable ex) {
        SensitivePathKind kind = classifySensitivePath(request);
        if (kind == SensitivePathKind.SUPPLIER_CREDENTIAL) { return safeCredentialParameterFailure(ex); }
        if (kind == SensitivePathKind.SUPPLIER_CALLBACK) { return safeSupplierCallbackParameterFailure(ex); }
        if (kind == SensitivePathKind.STANDARD_ORDER) { return safeStandardOrderParameterFailure(ex); }
        if (kind == SensitivePathKind.RECHARGE_FREQUENCY) { return safeParameterFailure(ex); }
        return null;
    }

    /**
     * 敏感路径固定错误码统一收敛策略：不回显异常明细，日志仅保留异常类型与 traceId；
     * 标准订单路径额外强制 Cache-Control: no-store。返回 null 表示非敏感路径。
     * 设计见 {@code docs/superpowers/specs/2026-10-02-standard-project-http-transport-design.md#2-供应链独立进程安全与缓存}。
     */
    private static CommonResult<?> safeErrorResponseIfSensitive(HttpServletRequest request, Throwable ex, ErrorCode errorCode) {
        SensitivePathKind kind = classifySensitivePath(request);
        if (kind == SensitivePathKind.SUPPLIER_CREDENTIAL) {
            ensureNoStore();
            log.warn("[供应商凭据HTTP错误] type={}, traceId={}", ex.getClass().getSimpleName(), TracerUtils.getTraceId());
            return CommonResult.error(errorCode);
        }
        if (kind == SensitivePathKind.SUPPLIER_CALLBACK) {
            ensureNoStore();
            log.warn("[供应商回调HTTP错误] type={}, traceId={}", ex.getClass().getSimpleName(), TracerUtils.getTraceId());
            return CommonResult.error(errorCode);
        }
        if (kind == SensitivePathKind.STANDARD_ORDER) {
            ensureNoStore();
            log.warn("[标准订单HTTP错误] type={}, traceId={}", ex.getClass().getSimpleName(), TracerUtils.getTraceId());
            return CommonResult.error(errorCode);
        }
        if (kind == SensitivePathKind.RECHARGE_FREQUENCY) {
            log.warn("[限频管理HTTP错误] type={}, traceId={}", ex.getClass().getSimpleName(), TracerUtils.getTraceId());
            return CommonResult.error(errorCode);
        }
        return null;
    }

    /**
     * 敏感路径业务拒绝统一收敛策略：透传业务错误码与文案（业务语义本身允许返回项目方），
     * 标准订单路径额外强制 no-store 并记录拒绝码日志。返回 null 表示非敏感路径。
     */
    private static CommonResult<?> safeBusinessRejectionIfSensitive(HttpServletRequest request, ServiceException ex) {
        SensitivePathKind kind = classifySensitivePath(request);
        if (kind == SensitivePathKind.SUPPLIER_CREDENTIAL) {
            ensureNoStore();
            log.warn("[供应商凭据业务拒绝] code={}, traceId={}", ex.getCode(), TracerUtils.getTraceId());
            return CommonResult.error(ex.getCode(), ex.getMessage());
        }
        if (kind == SensitivePathKind.SUPPLIER_CALLBACK) {
            ensureNoStore();
            log.warn("[供应商回调业务拒绝] code={}, traceId={}", ex.getCode(), TracerUtils.getTraceId());
            return CommonResult.error(ex.getCode(), ex.getMessage());
        }
        if (kind == SensitivePathKind.STANDARD_ORDER) {
            ensureNoStore();
            log.warn("[标准订单业务拒绝] code={}, message={}, traceId={}", ex.getCode(), ex.getMessage(), TracerUtils.getTraceId());
            return CommonResult.error(ex.getCode(), ex.getMessage());
        }
        if (kind == SensitivePathKind.RECHARGE_FREQUENCY) {
            log.warn("[限频管理业务拒绝] code={}, traceId={}", ex.getCode(), TracerUtils.getTraceId());
            return CommonResult.error(ex.getCode(), ex.getMessage());
        }
        return null;
    }

    /** 标准订单路径所有成功/错误响应统一强制 Cache-Control: no-store；设计见
     * {@code docs/superpowers/specs/2026-10-02-standard-project-http-transport-design.md#2-供应链独立进程安全与缓存}。 */
    private static void ensureNoStore() {
        HttpServletResponse response = ServletUtils.getResponse();
        if (response != null) {
            response.setHeader("Cache-Control", "no-store");
        }
    }

    /** 凭据参数异常可携带明文拒绝值，只保留类型和链路标识。设计见
     * {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#151-凭据写入日志保护}。 */
    private static CommonResult<?> safeCredentialParameterFailure(Throwable ex) {
        ensureNoStore();
        log.warn("[供应商凭据参数错误] type={}, traceId={}", ex.getClass().getSimpleName(), TracerUtils.getTraceId());
        return CommonResult.error(BAD_REQUEST.getCode(), "请求参数不正确");
    }

    /** 共享回调日志只保留异常类型；端点 ACK/错误合同由供应链入口负责。
     * 设计见 docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#513-临时本地认证与http闭环合同2026-10-18用户批准。
     */
    private static CommonResult<?> safeSupplierCallbackParameterFailure(Throwable ex) {
        ensureNoStore();
        log.warn("[供应商回调参数错误] type={}, traceId={}", ex.getClass().getSimpleName(), TracerUtils.getTraceId());
        return CommonResult.error(BAD_REQUEST.getCode(), "请求参数不正确");
    }

    private static CommonResult<?> safeStandardOrderParameterFailure(Throwable ex) {
        ensureNoStore();
        log.warn("[标准订单参数错误] type={}, traceId={}", ex.getClass().getSimpleName(), TracerUtils.getTraceId());
        return CommonResult.error(BAD_REQUEST.getCode(), "请求参数不正确");
    }

    private static boolean isParameterFailure(Throwable ex) {
        return ex instanceof MissingServletRequestParameterException || ex instanceof MethodArgumentTypeMismatchException
                || ex instanceof BindException || ex instanceof ValidationException || ex instanceof HttpMessageNotReadableException
                || ex instanceof org.springframework.web.method.annotation.HandlerMethodValidationException
                || ex instanceof org.springframework.validation.method.MethodValidationException;
    }

    private static CommonResult<?> safeParameterFailure(Throwable ex) {
        // 不打印Throwable：绑定异常包含rejectedValue，解析异常包含原JSON。
        String field = null;
        if (ex instanceof BindException binding && binding.getFieldError() != null) { field = binding.getFieldError().getField(); }
        if (ex instanceof MethodArgumentTypeMismatchException typed) { field = typed.getName(); }
        // 字段名也只允许静态DTO字段，避免恶意属性路径进入日志。
        if (!Set.of("mobile", "view", "pageNo", "pageSize", "buyerId", "projectId", "platformSpuCode", "orderNo", "buyerOrderNo",
                "enqueuedFrom", "enqueuedTo", "actionNo", "expectedVersion", "expectedGeneration", "expectedHolderOrderNo", "reason", "entryId", "queueId").contains(Objects.toString(field, ""))) {
            field = "[protected]";
        }
        log.warn("[限频管理参数错误] type={}, field={}, traceId={}", ex.getClass().getSimpleName(), field, TracerUtils.getTraceId());
        return CommonResult.error(BAD_REQUEST.getCode(), "请求参数不正确");
    }

    /**
     * 处理 Table 不存在的异常情况
     *
     * @param ex 异常
     * @return 如果是 Table 不存在的异常，则返回对应的 CommonResult
     */
    private CommonResult<?> handleTableNotExists(Throwable ex) {
        String message = ExceptionUtil.getRootCauseMessage(ex);
        if (!message.contains("doesn't exist")) {
            return null;
        }
        // 1. 商城系统
        if (StrUtil.containsAny(message, "product_", "promotion_", "trade_")) {
            log.error("[商城系统 aifuli-module-mall - 已禁用][参考 https://cloud.iocoder.cn/mall/build/ 开启]");
            return CommonResult.error(NOT_IMPLEMENTED.getCode(),
                    "[商城系统 aifuli-module-mall - 已禁用][参考 https://cloud.iocoder.cn/mall/build/ 开启]");
        }
        // 2. ERP 系统
        if (message.contains("erp_")) {
            log.error("[ERP 系统 aifuli-module-erp - 表结构未导入][参考 https://cloud.iocoder.cn/erp/build/ 开启]");
            return CommonResult.error(NOT_IMPLEMENTED.getCode(),
                    "[ERP 系统 aifuli-module-erp - 表结构未导入][参考 https://cloud.iocoder.cn/erp/build/ 开启]");
        }
        // 3. 支付平台
        if (message.contains("pay_")) {
            log.error("[支付模块 aifuli-module-pay - 表结构未导入][参考 https://cloud.iocoder.cn/pay/build/ 开启]");
            return CommonResult.error(NOT_IMPLEMENTED.getCode(),
                    "[支付模块 aifuli-module-pay - 表结构未导入][参考 https://cloud.iocoder.cn/pay/build/ 开启]");
        }
        return null;
    }

}
