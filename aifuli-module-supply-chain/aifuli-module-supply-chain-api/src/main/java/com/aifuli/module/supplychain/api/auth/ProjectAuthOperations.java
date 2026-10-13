package com.aifuli.module.supplychain.api.auth;

import com.aifuli.module.supplychain.enums.ApiConstants;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;

/**
 * 项目应用统一认证受保护操作注册表，客户端签名与服务端验签同源。
 *
 * <p>登记 INSTORE 四组 API 全部 10 个操作及标准商品 HTTP 三个操作的精确路径、scope 要求与契约版本；
 * 服务端过滤器按 (HTTP 方法, 路径) 精确匹配，前缀内未登记路径一律拒绝，杜绝保护缺口；
 * operationCode 由类初始化时构建的不可变索引统一解析，重复登记直接失败，客户端与服务端
 * 禁止各自重建查询规则。
 * 详见 {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#5-operationcode-注册表}。</p>
 */
public final class ProjectAuthOperations {

    /** scope 声明类型：决定签名串中哪个 scope 字段必填、产出哪型可信上下文。 */
    public enum ScopeType {

        /** 无用户/渠道 scope 声明；回调读取项目上下文，标准商品读取操作匹配的应用快照。 */
        NONE,

        /** 渠道编码声明（渠道命令/会员），产出 ChannelContext。 */
        CHANNEL_CODE,

        /** 采购商用户标识声明（买家入口），产出 BuyerContext。 */
        BUYER_USER_ID
    }

    /** 标准订单 HTTP 独立入口，不使用 RPC 前缀。设计见 2026-09-30 详细设计 §2.1。 */
    public static final String STANDARD_ORDERS_PATH = "/open-api/supply-chain/project/v1/orders";
    public static final String STANDARD_ORDER_CREATE = "STANDARD_ORDER_CREATE";
    public static final String STANDARD_ORDER_QUERY = "STANDARD_ORDER_QUERY";
    public static final String STANDARD_ORDER_ASSET_QUERY = "STANDARD_ORDER_ASSET_QUERY";
    /**
     * v2独立操作与签名字节合同，不借用v1操作码。
     * 设计见 docs/superpowers/specs/2026-10-12-platform-sku-order-contract-design.md#31-三个明确-v2-操作。
     */
    public static final String STANDARD_ORDERS_V2_PATH = "/open-api/supply-chain/project/v2/orders";
    public static final String STANDARD_ORDER_CREATE_V2 = "STANDARD_ORDER_CREATE_V2";
    public static final String STANDARD_ORDER_QUERY_V2 = "STANDARD_ORDER_QUERY_V2";
    public static final String STANDARD_ORDER_ASSET_QUERY_V2 = "STANDARD_ORDER_ASSET_QUERY_V2";
    public static final String CAPABILITY_STANDARD_PRODUCT = "STANDARD_PRODUCT";

    /**
     * 注册操作的封闭项目状态策略；状态编码由服务端认证边界判断，API 不依赖交易领域。
     * 详见 {@code docs/superpowers/specs/2026-09-30-project-signed-standard-order-http-design.md#21-认证实施边界}。
     */
    public enum ProjectStatePolicy {
        ACTIVE_ONLY,
        ACTIVE_OR_SUSPENDED
    }

    /** 受保护操作定义：包含精确方法/路径、scope、契约版本、能力和封闭项目状态策略。 */
    public record Operation(String operationCode, String httpMethod, String path,
                            ScopeType scopeType, String contractVersion, String requiredCapability,
                            ProjectStatePolicy projectStatePolicy) {

        public Operation {
            requireNonBlank(operationCode, "operationCode");
            requireNonBlank(httpMethod, "httpMethod");
            requireNonBlank(path, "path");
            Objects.requireNonNull(scopeType, "scopeType");
            requireNonBlank(contractVersion, "contractVersion");
            requireNonBlank(requiredCapability, "requiredCapability");
            Objects.requireNonNull(projectStatePolicy, "projectStatePolicy");
        }

        /** 保留旧六参数构造，既有调用仍只允许 ACTIVE 项目。 */
        public Operation(String operationCode, String httpMethod, String path,
                         ScopeType scopeType, String contractVersion, String requiredCapability) {
            this(operationCode, httpMethod, path, scopeType, contractVersion, requiredCapability,
                    ProjectStatePolicy.ACTIVE_ONLY);
        }

        /** 保留旧五参数构造契约；既有操作均属于 INSTORE。 */
        public Operation(String operationCode, String httpMethod, String path,
                         ScopeType scopeType, String contractVersion) {
            this(operationCode, httpMethod, path, scopeType, contractVersion, CAPABILITY_INSTORE);
        }
    }

    /** 受保护路径前缀：过滤器注册与安全放行配置共用。 */
    public static final List<String> PROTECTED_PREFIXES = List.of(
            ApiConstants.PREFIX + "/instore/channel-command",
            ApiConstants.PREFIX + "/instore/channel-member",
            ApiConstants.PREFIX + "/instore/buyer-entry",
            ApiConstants.PREFIX + "/buyer/instore/payment",
            STANDARD_ORDERS_PATH,
            STANDARD_ORDERS_V2_PATH);

    /** 当前契约版本；多传输形态出现时随规范化器一并升级。 */
    private static final String CONTRACT_VERSION = "1.0.0";
    private static final String CAPABILITY_INSTORE = "INSTORE";

    /** INSTORE 十操作与标准商品三个操作；登记认证合同不代表业务 Controller 已开放。 */
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
                    ApiConstants.PREFIX + "/buyer/instore/payment/reportRefundResult", ScopeType.NONE),
            standardOperation(STANDARD_ORDER_CREATE, STANDARD_ORDERS_PATH, ProjectStatePolicy.ACTIVE_ONLY),
            standardOperation(STANDARD_ORDER_QUERY, STANDARD_ORDERS_PATH + "/query",
                    ProjectStatePolicy.ACTIVE_OR_SUSPENDED),
            standardOperation(STANDARD_ORDER_ASSET_QUERY, STANDARD_ORDERS_PATH + "/assets/query",
                    ProjectStatePolicy.ACTIVE_ONLY),
            new Operation(STANDARD_ORDER_CREATE_V2, "POST", STANDARD_ORDERS_V2_PATH, ScopeType.NONE,
                    "2.0.0", CAPABILITY_STANDARD_PRODUCT, ProjectStatePolicy.ACTIVE_ONLY),
            new Operation(STANDARD_ORDER_QUERY_V2, "POST", STANDARD_ORDERS_V2_PATH + "/query", ScopeType.NONE,
                    "2.0.0", CAPABILITY_STANDARD_PRODUCT, ProjectStatePolicy.ACTIVE_OR_SUSPENDED),
            new Operation(STANDARD_ORDER_ASSET_QUERY_V2, "POST", STANDARD_ORDERS_V2_PATH + "/assets/query", ScopeType.NONE,
                    "2.0.0", CAPABILITY_STANDARD_PRODUCT, ProjectStatePolicy.ACTIVE_ONLY));

    /** operationCode 唯一不可变索引；重复键在类初始化阶段失败，禁止静默覆盖。 */
    private static final Map<String, Operation> OPERATIONS_BY_CODE = indexByOperationCode(OPERATIONS);

    private ProjectAuthOperations() {
    }

    private static Operation operation(String operationCode, String path, ScopeType scopeType) {
        return new Operation(operationCode, "POST", path, scopeType, CONTRACT_VERSION, CAPABILITY_INSTORE);
    }

    private static Operation standardOperation(String code, String path, ProjectStatePolicy policy) {
        return new Operation(code, "POST", path, ScopeType.NONE, CONTRACT_VERSION,
                CAPABILITY_STANDARD_PRODUCT, policy);
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
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
