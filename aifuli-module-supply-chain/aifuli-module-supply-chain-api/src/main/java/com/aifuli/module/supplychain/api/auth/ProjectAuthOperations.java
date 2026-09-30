package com.aifuli.module.supplychain.api.auth;

import com.aifuli.module.supplychain.enums.ApiConstants;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 项目应用统一认证受保护操作注册表，客户端签名与服务端验签同源。
 *
 * <p>登记 INSTORE 四组 API 全部 10 个受保护操作的精确路径、scope 要求与契约版本；
 * 服务端过滤器按 (HTTP 方法, 路径) 精确匹配，前缀内未登记路径一律拒绝，杜绝保护缺口；
 * operationCode 由类初始化时构建的不可变索引统一解析，重复登记直接失败，客户端与服务端
 * 禁止各自重建查询规则。
 * 详见 {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#5-operationcode-注册表}。</p>
 */
public final class ProjectAuthOperations {

    /** scope 声明类型：决定签名串中哪个 scope 字段必填、产出哪型可信上下文。 */
    public enum ScopeType {

        /** 无 scope 声明（SaaS 收银台回调），产出 ProjectContext。 */
        NONE,

        /** 渠道编码声明（渠道命令/会员），产出 ChannelContext。 */
        CHANNEL_CODE,

        /** 采购商用户标识声明（买家入口），产出 BuyerContext。 */
        BUYER_USER_ID
    }

    /** 受保护操作定义：operationCode、精确方法与路径、scope 要求、契约版本。 */
    public record Operation(String operationCode, String httpMethod, String path,
                            ScopeType scopeType, String contractVersion) {
    }

    /** 受保护路径前缀：过滤器注册与安全放行配置共用。 */
    public static final List<String> PROTECTED_PREFIXES = List.of(
            ApiConstants.PREFIX + "/instore/channel-command",
            ApiConstants.PREFIX + "/instore/channel-member",
            ApiConstants.PREFIX + "/instore/buyer-entry",
            ApiConstants.PREFIX + "/buyer/instore/payment");

    /** 当前契约版本；多传输形态出现时随规范化器一并升级。 */
    private static final String CONTRACT_VERSION = "1.0.0";

    /** INSTORE 四组 API 全部 10 个受保护操作。 */
    public static final List<Operation> OPERATIONS = List.of(
            operation("INSTORE_CREATE_PAYMENT_ORDER",
                    ApiConstants.PREFIX + "/instore/channel-command/createPaymentOrder", ScopeType.CHANNEL_CODE),
            operation("INSTORE_QUERY_PAYMENT_STATUS",
                    ApiConstants.PREFIX + "/instore/channel-command/queryPaymentStatus", ScopeType.CHANNEL_CODE),
            operation("INSTORE_CANCEL_PAYMENT_FLOW",
                    ApiConstants.PREFIX + "/instore/channel-command/cancelPaymentFlow", ScopeType.CHANNEL_CODE),
            operation("INSTORE_ACCEPT_REFUND",
                    ApiConstants.PREFIX + "/instore/channel-command/acceptRefund", ScopeType.CHANNEL_CODE),
            operation("INSTORE_QUERY_REFUND_STATUS",
                    ApiConstants.PREFIX + "/instore/channel-command/queryRefundStatus", ScopeType.CHANNEL_CODE),
            operation("INSTORE_RESOLVE_MEMBER",
                    ApiConstants.PREFIX + "/instore/channel-member/resolve", ScopeType.CHANNEL_CODE),
            operation("INSTORE_HOME_ENTRY",
                    ApiConstants.PREFIX + "/instore/buyer-entry/home", ScopeType.BUYER_USER_ID),
            operation("INSTORE_ORDER_DETAIL_ENTRY",
                    ApiConstants.PREFIX + "/instore/buyer-entry/detail", ScopeType.BUYER_USER_ID),
            operation("INSTORE_CONFIRM_PAYMENT_CALLBACK",
                    ApiConstants.PREFIX + "/buyer/instore/payment/confirmPayment", ScopeType.NONE),
            operation("INSTORE_REPORT_REFUND_RESULT_CALLBACK",
                    ApiConstants.PREFIX + "/buyer/instore/payment/reportRefundResult", ScopeType.NONE));

    /** operationCode 唯一不可变索引；重复键在类初始化阶段失败，禁止静默覆盖。 */
    private static final Map<String, Operation> OPERATIONS_BY_CODE = indexByOperationCode(OPERATIONS);

    private ProjectAuthOperations() {
    }

    private static Operation operation(String operationCode, String path, ScopeType scopeType) {
        return new Operation(operationCode, "POST", path, scopeType, CONTRACT_VERSION);
    }

    /**
     * 按 HTTP 方法与路径精确匹配受保护操作。
     *
     * @param httpMethod HTTP 方法，大小写不敏感
     * @param path       请求路径，不含 context path 与 query
     * @return 匹配的操作；未登记路径返回空
     */
    public static Optional<Operation> findByMethodAndPath(String httpMethod, String path) {
        return OPERATIONS.stream()
                .filter(op -> op.httpMethod().equalsIgnoreCase(httpMethod) && op.path().equals(path))
                .findFirst();
    }

    /**
     * 按稳定 operationCode 查询受保护操作，供服务端可信上下文与验签链路共用。
     *
     * @param operationCode 稳定操作码；null 或未知值返回空
     * @return 唯一登记的操作
     */
    public static Optional<Operation> findByOperationCode(String operationCode) {
        if (operationCode == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(OPERATIONS_BY_CODE.get(operationCode));
    }

    /** 构建 operationCode 索引；包级入口仅供契约测试证明重复失败与不可变语义。 */
    static Map<String, Operation> indexByOperationCode(List<Operation> operations) {
        LinkedHashMap<String, Operation> index = new LinkedHashMap<>();
        for (Operation operation : operations) {
            Operation existing = index.putIfAbsent(operation.operationCode(), operation);
            if (existing != null) {
                throw new IllegalStateException("项目认证 operationCode 重复登记");
            }
        }
        return Map.copyOf(index);
    }
}
