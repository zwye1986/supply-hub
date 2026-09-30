# INSTORE 支付/退款 API 契约层实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 INSTORE 渠道暴露支付/退款 Wire 契约——`InstoreChannelCommandApi`（旅通接口②③④⑤⑥）与 `InstoreBuyerCashierCallbackApi`（SaaS 收银台支付/退款回调），按方案 A 用薄 ApiImpl + Facade Wire 编排落地。

**Architecture:** API 模块新增 2 个 Feign 接口 + 12 个 DTO（上下文派生字段一律不进 Wire）；server 端 `ProjectCallContextAccessor` 扩展 `ProjectContext`（回调信任域）；`InstoreChannelCommandFacade` 新增 Wire 建单方法（内部解析稳定绑定拿 buyerId + 当前 CASHIER 档案版本，再委托既有 `createPayment`），两个查询方法返回值包装为 Facade 自有视图 record 使 RPC 层不依赖 border 包；`interfaceapi.rpc` 新增两个 ApiImpl 只做上下文+日志+映射。

**Tech Stack:** Spring Cloud OpenFeign、Bean Validation、Lombok、Mockito、ArchUnit。

**设计文档:** `docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md`

**关键约束（全部任务通用）:**
- DTO 字段注释必须与 DDL COMMENT 语义一致（开发规范 §5）；重要类/方法写中文注释并链接设计文档章节（§4）。
- 禁止业务魔法值；金额单位一律为分；状态字典写进 `@Schema` description。
- 日志记录业务键（单号、金额、状态），`userCode` 全值与 `cashierUrl` 不得进日志/toString。
- Maven 测试命令统一加 `-o`（离线）与 `-Dsurefire.failIfNoSpecifiedTests=false`。
- 所有路径相对仓库根 `/Users/mac/Documents/codeSpace/code_bak/aifuli-mall-pro`。

---

### Task 1: API 契约层——契约测试 + 12 个 Wire DTO + 2 个接口

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/test/java/com/aifuli/module/supplychain/api/instore/InstorePaymentCommandApiContractTest.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/api/instore/InstoreChannelCommandApi.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/api/instore/InstoreBuyerCashierCallbackApi.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/api/instore/dto/CreatePaymentOrderReqDTO.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/api/instore/dto/QueryPaymentStatusReqDTO.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/api/instore/dto/CancelPaymentFlowReqDTO.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/api/instore/dto/AcceptRefundReqDTO.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/api/instore/dto/QueryRefundStatusReqDTO.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/api/instore/dto/PaymentTicketRespDTO.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/api/instore/dto/PaymentStatusRespDTO.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/api/instore/dto/CancelPaymentFlowRespDTO.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/api/instore/dto/RefundAcceptanceRespDTO.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/api/instore/dto/RefundStatusRespDTO.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/api/instore/dto/ConfirmPaymentReqDTO.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/api/instore/dto/ReportRefundResultReqDTO.java`

- [x] **Step 1: 写失败的契约测试**

完整文件 `InstorePaymentCommandApiContractTest.java`（模式同 `InstoreMemberEntryApiContractTest`，同样放在 API 模块）：

```java
package com.aifuli.module.supplychain.api.instore;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.instore.dto.AcceptRefundReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.CancelPaymentFlowReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.CancelPaymentFlowRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.ConfirmPaymentReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.CreatePaymentOrderReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.PaymentStatusRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.PaymentTicketRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.QueryPaymentStatusReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.QueryRefundStatusReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.RefundAcceptanceRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.RefundStatusRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.ReportRefundResultReqDTO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 到店购支付/退款命令与收银台回调 RPC 的字段边界与校验合同。
 *
 * <p>上下文派生字段（projectId/channelCode/buyerId/路由字段）禁止进入 Wire DTO。
 * 详见 {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#2-api-层}。</p>
 */
class InstorePaymentCommandApiContractTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void keepsExactExternalDtoFieldBoundaries() {
        assertBusinessFields(CreatePaymentOrderReqDTO.class, Map.of(
                "channelOrderId", String.class,
                "tradeId", String.class,
                "userCode", String.class,
                "payAmount", Long.class,
                "orderType", Integer.class,
                "totalAmount", Long.class,
                "companyCode", String.class,
                "externalMerchantId", String.class,
                "shopCode", String.class,
                "shopName", String.class,
                "subject", String.class,
                "traceId", String.class));
        assertBusinessFields(QueryPaymentStatusReqDTO.class, Map.of(
                "tradeId", String.class, "traceId", String.class));
        assertBusinessFields(CancelPaymentFlowReqDTO.class, Map.of(
                "tradeId", String.class, "traceId", String.class));
        assertBusinessFields(AcceptRefundReqDTO.class, Map.of(
                "channelRefundNo", String.class,
                "tradeId", String.class,
                "refundType", String.class,
                "channelRefundAmount", Long.class,
                "traceId", String.class));
        assertBusinessFields(QueryRefundStatusReqDTO.class, Map.of(
                "channelRefundNo", String.class, "traceId", String.class));
        assertBusinessFields(PaymentTicketRespDTO.class, Map.of(
                "orderNo", String.class,
                "outTradeNo", String.class,
                "cashierUrl", String.class,
                "payStatus", Integer.class));
        assertBusinessFields(PaymentStatusRespDTO.class, Map.of(
                "paymentFlowNo", String.class,
                "outTradeNo", String.class,
                "tradeId", String.class,
                "payStatus", Integer.class,
                "payAmount", Long.class,
                "buyerPaidAmount", Long.class,
                "cashierReference", String.class,
                "externalTransactionId", String.class));
        assertBusinessFields(CancelPaymentFlowRespDTO.class, Map.of(
                "outTradeNo", String.class,
                "refundCaseNo", String.class,
                "convertedToRefund", Boolean.class,
                "claimedForDispatch", Boolean.class));
        assertBusinessFields(RefundAcceptanceRespDTO.class, Map.of(
                "refundCaseNo", String.class,
                "refundStatus", Integer.class,
                "claimedForDispatch", Boolean.class));
        assertBusinessFields(RefundStatusRespDTO.class, Map.of(
                "refundCaseNo", String.class,
                "channelRefundNo", String.class,
                "refundType", String.class,
                "status", Integer.class,
                "channelRefundAmount", Long.class,
                "buyerActualRefundAmount", Long.class));
        assertBusinessFields(ConfirmPaymentReqDTO.class, Map.of(
                "outTradeNo", String.class,
                "actualAmountCny", Long.class,
                "cashierOrderNo", String.class,
                "cashierTransactionNo", String.class,
                "traceId", String.class));
        assertBusinessFields(ReportRefundResultReqDTO.class, Map.of(
                "refundCaseNo", String.class,
                "actualAmountCny", Long.class,
                "cashierRefundReference", String.class,
                "traceId", String.class));
    }

    @Test
    void forbidsInternalRoutingAndIdentityFieldsInWireDtos() {
        Set<String> forbiddenFields = Set.of("projectId", "buyerId", "buyerUserId", "channelCode",
                "sourceSystem", "externalTenantId", "cashierProfileVersionId", "appId", "keyVersion");
        for (Class<?> dtoType : Set.of(CreatePaymentOrderReqDTO.class, QueryPaymentStatusReqDTO.class,
                CancelPaymentFlowReqDTO.class, AcceptRefundReqDTO.class, QueryRefundStatusReqDTO.class,
                PaymentTicketRespDTO.class, PaymentStatusRespDTO.class, CancelPaymentFlowRespDTO.class,
                RefundAcceptanceRespDTO.class, RefundStatusRespDTO.class,
                ConfirmPaymentReqDTO.class, ReportRefundResultReqDTO.class)) {
            assertThat(businessFields(dtoType).keySet()).doesNotContainAnyElementsOf(forbiddenFields);
        }
    }

    @Test
    void rejectsEveryRequiredField() {
        CreatePaymentOrderReqDTO create = new CreatePaymentOrderReqDTO();
        create.setPayAmount(10_000L);
        create.setOrderType(1);
        create.setTotalAmount(10_000L);
        assertViolationProperties(create, "channelOrderId", "tradeId", "userCode",
                "companyCode", "externalMerchantId");

        CreatePaymentOrderReqDTO zeroAmount = validCreateRequest();
        zeroAmount.setPayAmount(0L);
        assertViolationProperties(zeroAmount, "payAmount");

        QueryPaymentStatusReqDTO query = new QueryPaymentStatusReqDTO();
        query.setTradeId(" ");
        assertViolationProperties(query, "tradeId");

        CancelPaymentFlowReqDTO cancel = new CancelPaymentFlowReqDTO();
        cancel.setTradeId(" ");
        assertViolationProperties(cancel, "tradeId");

        AcceptRefundReqDTO refund = new AcceptRefundReqDTO();
        refund.setChannelRefundAmount(300L);
        assertViolationProperties(refund, "channelRefundNo", "tradeId", "refundType");

        QueryRefundStatusReqDTO queryRefund = new QueryRefundStatusReqDTO();
        queryRefund.setChannelRefundNo(" ");
        assertViolationProperties(queryRefund, "channelRefundNo");

        ConfirmPaymentReqDTO confirm = new ConfirmPaymentReqDTO();
        confirm.setActualAmountCny(10_000L);
        assertViolationProperties(confirm, "outTradeNo");

        ReportRefundResultReqDTO report = new ReportRefundResultReqDTO();
        report.setActualAmountCny(300L);
        assertViolationProperties(report, "refundCaseNo");
    }

    @Test
    void acceptsCompleteRequests() {
        assertThat(validator.validate(validCreateRequest())).isEmpty();

        QueryPaymentStatusReqDTO query = new QueryPaymentStatusReqDTO();
        query.setTradeId("TRADE001");
        assertThat(validator.validate(query)).isEmpty();

        CancelPaymentFlowReqDTO cancel = new CancelPaymentFlowReqDTO();
        cancel.setTradeId("TRADE001");
        assertThat(validator.validate(cancel)).isEmpty();

        AcceptRefundReqDTO refund = new AcceptRefundReqDTO();
        refund.setChannelRefundNo("R9");
        refund.setTradeId("TRADE001");
        refund.setRefundType("CHANNEL_REFUND");
        refund.setChannelRefundAmount(300L);
        assertThat(validator.validate(refund)).isEmpty();

        QueryRefundStatusReqDTO queryRefund = new QueryRefundStatusReqDTO();
        queryRefund.setChannelRefundNo("R9");
        assertThat(validator.validate(queryRefund)).isEmpty();

        ConfirmPaymentReqDTO confirm = new ConfirmPaymentReqDTO();
        confirm.setOutTradeNo("IPF_OTN");
        confirm.setActualAmountCny(10_000L);
        assertThat(validator.validate(confirm)).isEmpty();

        ReportRefundResultReqDTO report = new ReportRefundResultReqDTO();
        report.setRefundCaseNo("IRC_9");
        report.setActualAmountCny(300L);
        assertThat(validator.validate(report)).isEmpty();
    }

    @Test
    void enforcesSpecifiedRequestFieldLengthBoundaries() {
        CreatePaymentOrderReqDTO request = validCreateRequest();
        assertThat(validator.validate(request)).isEmpty();

        request.setChannelOrderId("c".repeat(129));
        assertViolationProperties(request, "channelOrderId");
        request.setChannelOrderId("c".repeat(128));
        request.setTradeId("t".repeat(129));
        assertViolationProperties(request, "tradeId");
        request.setTradeId("t".repeat(128));
        request.setUserCode("u".repeat(129));
        assertViolationProperties(request, "userCode");
        request.setUserCode("u".repeat(128));
        request.setCompanyCode("c".repeat(33));
        assertViolationProperties(request, "companyCode");
        request.setCompanyCode("c".repeat(32));
        request.setExternalMerchantId("m".repeat(129));
        assertViolationProperties(request, "externalMerchantId");
        request.setExternalMerchantId("m".repeat(128));
        request.setShopCode("s".repeat(129));
        assertViolationProperties(request, "shopCode");
        request.setShopCode("s".repeat(128));
        request.setShopName("n".repeat(257));
        assertViolationProperties(request, "shopName");
        request.setShopName("n".repeat(256));
        request.setSubject("j".repeat(257));
        assertViolationProperties(request, "subject");
        request.setSubject("j".repeat(256));
        request.setTraceId("x".repeat(129));
        assertViolationProperties(request, "traceId");

        AcceptRefundReqDTO refund = new AcceptRefundReqDTO();
        refund.setChannelRefundNo("r".repeat(129));
        assertViolationProperties(refund, "channelRefundNo");
        refund.setChannelRefundNo("r".repeat(128));
        refund.setTradeId("t".repeat(128));
        refund.setRefundType("f".repeat(33));
        assertViolationProperties(refund, "refundType");
        refund.setRefundType("f".repeat(32));
        refund.setChannelRefundAmount(300L);

        ReportRefundResultReqDTO report = new ReportRefundResultReqDTO();
        report.setRefundCaseNo("i".repeat(33));
        assertViolationProperties(report, "refundCaseNo");
        report.setRefundCaseNo("i".repeat(32));
        report.setActualAmountCny(300L);
    }

    @Test
    void publishesOnlyCommonResultRpcMethodsWithExactPaths() throws Exception {
        assertApiMethod(InstoreChannelCommandApi.class, "createPaymentOrder",
                CreatePaymentOrderReqDTO.class, PaymentTicketRespDTO.class,
                "/rpc-api/supply-chain/instore/channel-command/createPaymentOrder");
        assertApiMethod(InstoreChannelCommandApi.class, "queryPaymentStatus",
                QueryPaymentStatusReqDTO.class, PaymentStatusRespDTO.class,
                "/rpc-api/supply-chain/instore/channel-command/queryPaymentStatus");
        assertApiMethod(InstoreChannelCommandApi.class, "cancelPaymentFlow",
                CancelPaymentFlowReqDTO.class, CancelPaymentFlowRespDTO.class,
                "/rpc-api/supply-chain/instore/channel-command/cancelPaymentFlow");
        assertApiMethod(InstoreChannelCommandApi.class, "acceptRefund",
                AcceptRefundReqDTO.class, RefundAcceptanceRespDTO.class,
                "/rpc-api/supply-chain/instore/channel-command/acceptRefund");
        assertApiMethod(InstoreChannelCommandApi.class, "queryRefundStatus",
                QueryRefundStatusReqDTO.class, RefundStatusRespDTO.class,
                "/rpc-api/supply-chain/instore/channel-command/queryRefundStatus");
        assertApiMethod(InstoreBuyerCashierCallbackApi.class, "confirmPayment",
                ConfirmPaymentReqDTO.class, Void.class,
                "/rpc-api/supply-chain/buyer/instore/payment/confirmPayment");
        assertApiMethod(InstoreBuyerCashierCallbackApi.class, "reportRefundResult",
                ReportRefundResultReqDTO.class, Void.class,
                "/rpc-api/supply-chain/buyer/instore/payment/reportRefundResult");
    }

    @Test
    void omitsUserCodeAndCashierUrlFromToString() {
        CreatePaymentOrderReqDTO create = new CreatePaymentOrderReqDTO();
        create.setUserCode("USER-CODE-SENTINEL");
        create.setTraceId("TRACE-SENTINEL");

        PaymentTicketRespDTO ticket = new PaymentTicketRespDTO();
        ticket.setCashierUrl("https://cashier/pay?token=CASHIER-URL-SENTINEL");

        assertThat(create.toString()).doesNotContain("USER-CODE-SENTINEL");
        assertThat(ticket.toString()).doesNotContain("CASHIER-URL-SENTINEL");
    }

    private static CreatePaymentOrderReqDTO validCreateRequest() {
        CreatePaymentOrderReqDTO request = new CreatePaymentOrderReqDTO();
        request.setChannelOrderId("ORD001");
        request.setTradeId("TRADE001");
        request.setUserCode("opaque-user");
        request.setPayAmount(10_000L);
        request.setOrderType(1);
        request.setTotalAmount(10_000L);
        request.setCompanyCode("COMPANY");
        request.setExternalMerchantId("MERCHANT1");
        return request;
    }

    private static void assertApiMethod(Class<?> apiType, String methodName, Class<?> requestType,
                                        Class<?> responseType, String expectedPath) throws Exception {
        Method method = apiType.getMethod(methodName, requestType);
        assertThat(method.getReturnType()).isEqualTo(CommonResult.class);
        assertThat(((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments())
                .containsExactly(responseType);
        assertThat(method.getAnnotation(PostMapping.class).value()).containsExactly(expectedPath);
    }

    private static void assertBusinessFields(Class<?> type, Map<String, Class<?>> expected) {
        assertThat(businessFields(type)).containsExactlyInAnyOrderEntriesOf(expected);
    }

    private static Map<String, Class<?>> businessFields(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !field.isSynthetic()
                        && !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .collect(Collectors.toMap(Field::getName, Field::getType));
    }

    private static void assertViolationProperties(Object request, String... expectedProperties) {
        Set<String> properties = validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
        assertThat(properties).containsExactlyInAnyOrder(expectedProperties);
    }
}
```

- [x] **Step 2: 运行契约测试确认 RED（编译失败即 RED）**

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-api -am \
  -Dtest=InstorePaymentCommandApiContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: COMPILATION ERROR（DTO 与接口不存在）。

- [x] **Step 3: 实现 2 个接口**

`InstoreChannelCommandApi.java`：

```java
package com.aifuli.module.supplychain.api.instore;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.instore.dto.AcceptRefundReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.CancelPaymentFlowReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.CancelPaymentFlowRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.CreatePaymentOrderReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.PaymentStatusRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.PaymentTicketRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.QueryPaymentStatusReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.QueryRefundStatusReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.RefundAcceptanceRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.RefundStatusRespDTO;
import com.aifuli.module.supplychain.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import jakarta.validation.Valid;

/**
 * 到店购支付/退款命令 RPC：渠道服务按旅通接口②③④⑤⑥调用。
 *
 * <p>projectId/channelCode 由服务端可信渠道上下文解析，禁止由调用方提交；
 * 幂等键为支付 {@code (channelCode, tradeId)}、退款 {@code (channelCode, refund_bn)}、
 * 订单头 {@code (channelCode, channelOrderId)}。详见
 * {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#2-api-层}。</p>
 */
@FeignClient(name = ApiConstants.NAME, primary = false)
@Tag(name = "RPC 服务 - 到店购支付退款命令")
public interface InstoreChannelCommandApi {

    String PREFIX = ApiConstants.PREFIX + "/instore/channel-command";

    @PostMapping(PREFIX + "/createPaymentOrder")
    @Operation(summary = "受理到店购支付单（旅通接口②）")
    CommonResult<PaymentTicketRespDTO> createPaymentOrder(
            @Valid @RequestBody CreatePaymentOrderReqDTO request);

    @PostMapping(PREFIX + "/queryPaymentStatus")
    @Operation(summary = "查询到店购支付状态（旅通接口③，本地非终态触发权威查询收敛）")
    CommonResult<PaymentStatusRespDTO> queryPaymentStatus(
            @Valid @RequestBody QueryPaymentStatusReqDTO request);

    @PostMapping(PREFIX + "/cancelPaymentFlow")
    @Operation(summary = "取消到店购支付流水（旅通接口④，已支付转全额退款）")
    CommonResult<CancelPaymentFlowRespDTO> cancelPaymentFlow(
            @Valid @RequestBody CancelPaymentFlowReqDTO request);

    @PostMapping(PREFIX + "/acceptRefund")
    @Operation(summary = "受理到店购退款（旅通接口⑤，同流水串行）")
    CommonResult<RefundAcceptanceRespDTO> acceptRefund(
            @Valid @RequestBody AcceptRefundReqDTO request);

    @PostMapping(PREFIX + "/queryRefundStatus")
    @Operation(summary = "查询到店购退款状态（旅通接口⑥，非终态按原退款号权威查询）")
    CommonResult<RefundStatusRespDTO> queryRefundStatus(
            @Valid @RequestBody QueryRefundStatusReqDTO request);
}
```

`InstoreBuyerCashierCallbackApi.java`：

```java
package com.aifuli.module.supplychain.api.instore;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.instore.dto.ConfirmPaymentReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.ReportRefundResultReqDTO;
import com.aifuli.module.supplychain.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import jakarta.validation.Valid;

/**
 * SaaS 商城收银台回调 RPC：支付确认与退款结果上报。
 *
 * <p>回调只提交原业务号与实际金额，归属按 outTradeNo/refundCaseNo 加载订单固化数据，
 * 已认证 SaaS 租户主体由可信项目上下文核验，回调参数不得改变归属。详见
 * {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#23-回调侧-dto-字段边界}。</p>
 */
@FeignClient(name = ApiConstants.NAME, primary = false)
@Tag(name = "RPC 服务 - 到店购收银台回调")
public interface InstoreBuyerCashierCallbackApi {

    String PREFIX = ApiConstants.PREFIX + "/buyer/instore/payment";

    @PostMapping(PREFIX + "/confirmPayment")
    @Operation(summary = "确认到店购支付成功（按 outTradeNo 收敛）")
    CommonResult<Void> confirmPayment(@Valid @RequestBody ConfirmPaymentReqDTO request);

    @PostMapping(PREFIX + "/reportRefundResult")
    @Operation(summary = "上报到店购退款结果（按 refundCaseNo 收敛）")
    CommonResult<Void> reportRefundResult(@Valid @RequestBody ReportRefundResultReqDTO request);
}
```

- [x] **Step 4: 实现 12 个 DTO**

命令侧请求（注释与 DDL COMMENT 逐字段一致，来源 `013-instore-channel.sql` L74-124、`001-identity-and-master.sql` L139）。

`CreatePaymentOrderReqDTO.java`：

```java
package com.aifuli.module.supplychain.api.instore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

import java.io.Serializable;

/**
 * 到店购建单请求：渠道按旅通接口②提交的支付单受理数据。
 *
 * <p>字段语义与 013 迁移 DDL COMMENT 一致；channelCode/projectId 来自服务端可信渠道上下文，
 * 不允许调用方提交。详见
 * {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#21-命令侧-dto-字段边界}。</p>
 */
@Schema(description = "到店购建单请求")
@Data
public class CreatePaymentOrderReqDTO implements Serializable {

    @Schema(description = "旅通订单号order_id，与channel_code构成永久幂等键",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "RT-ORDER-001")
    @NotBlank(message = "旅通订单号不能为空")
    @Size(max = 128, message = "旅通订单号不能超过 128 个字符")
    private String channelOrderId;

    @Schema(description = "旅通支付流水号trade_id，与channel_code构成支付永久幂等键",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "RT-TRADE-001")
    @NotBlank(message = "旅通支付流水号不能为空")
    @Size(max = 128, message = "旅通支付流水号不能超过 128 个字符")
    private String tradeId;

    @Schema(description = "旅通会员编号user_code，受理时固化用于归属二次校验",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "opaque-user")
    @NotBlank(message = "旅通会员编号不能为空")
    @Size(max = 128, message = "旅通会员编号不能超过 128 个字符")
    @ToString.Exclude
    private String userCode;

    @Schema(description = "本笔支付流水金额，单位为分",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "10000")
    @NotNull(message = "支付流水金额不能为空")
    @Positive(message = "支付流水金额必须大于 0")
    private Long payAmount;

    @Schema(description = "旅通支付单类型order_type：1线下扫码购，2动态码",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "旅通支付单类型不能为空")
    private Integer orderType;

    @Schema(description = "订单应付总额total_amount，单位为分，旅通建单传入",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "10000")
    @NotNull(message = "订单应付总额不能为空")
    @Positive(message = "订单应付总额必须大于 0")
    private Long totalAmount;

    @Schema(description = "旅通企业组编号company_code，受理时固化",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "COMPANY-001")
    @NotBlank(message = "旅通企业组编号不能为空")
    @Size(max = 32, message = "旅通企业组编号不能超过 32 个字符")
    private String companyCode;

    @Schema(description = "渠道中的稳定外部商户标识",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "MERCHANT-001")
    @NotBlank(message = "外部商户标识不能为空")
    @Size(max = 128, message = "外部商户标识不能超过 128 个字符")
    private String externalMerchantId;

    @Schema(description = "渠道外部门店编号shop_code", example = "SHOP-001")
    @Size(max = 128, message = "门店编号不能超过 128 个字符")
    private String shopCode;

    @Schema(description = "渠道外部门店名称shop_name快照", example = "大润发门店")
    @Size(max = 256, message = "门店名称不能超过 256 个字符")
    private String shopName;

    @Schema(description = "交易主题/商品摘要，外呼收银台时透传", example = "到店购")
    @Size(max = 256, message = "交易主题不能超过 256 个字符")
    private String subject;

    @Schema(description = "链路追踪标识，缺省由服务端生成", example = "trace-001")
    @Size(max = 128, message = "链路追踪标识不能超过 128 个字符")
    private String traceId;
}
```

`QueryPaymentStatusReqDTO.java`：

```java
package com.aifuli.module.supplychain.api.instore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 到店购支付状态查询请求（旅通接口③）。
 *
 * <p>本地非终态时服务端按原 outTradeNo 权威查单收敛后返回最新投影。详见
 * {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#21-命令侧-dto-字段边界}。</p>
 */
@Schema(description = "到店购支付状态查询请求")
@Data
public class QueryPaymentStatusReqDTO implements Serializable {

    @Schema(description = "旅通支付流水号trade_id，与channel_code构成支付永久幂等键",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "RT-TRADE-001")
    @NotBlank(message = "旅通支付流水号不能为空")
    @Size(max = 128, message = "旅通支付流水号不能超过 128 个字符")
    private String tradeId;

    @Schema(description = "链路追踪标识，缺省由服务端生成", example = "trace-q-001")
    @Size(max = 128, message = "链路追踪标识不能超过 128 个字符")
    private String traceId;
}
```

`CancelPaymentFlowReqDTO.java`：

```java
package com.aifuli.module.supplychain.api.instore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 到店购支付流水取消请求（旅通接口④）。
 *
 * <p>未支付则关单；已支付且从未存在退款协同时转全额 CANCEL_REFUND 退款。详见
 * {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#21-命令侧-dto-字段边界}。</p>
 */
@Schema(description = "到店购支付流水取消请求")
@Data
public class CancelPaymentFlowReqDTO implements Serializable {

    @Schema(description = "旅通支付流水号trade_id，与channel_code构成支付永久幂等键",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "RT-TRADE-001")
    @NotBlank(message = "旅通支付流水号不能为空")
    @Size(max = 128, message = "旅通支付流水号不能超过 128 个字符")
    private String tradeId;

    @Schema(description = "链路追踪标识，缺省由服务端生成", example = "trace-cancel-001")
    @Size(max = 128, message = "链路追踪标识不能超过 128 个字符")
    private String traceId;
}
```

`AcceptRefundReqDTO.java`：

```java
package com.aifuli.module.supplychain.api.instore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 到店购退款受理请求（旅通接口⑤）。
 *
 * <p>同一支付流水至多一个活动退款，其余 Case 串行排队；可退金额 = 已付 − 已累计 − 在途。
 * 详见 {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#21-命令侧-dto-字段边界}。</p>
 */
@Schema(description = "到店购退款受理请求")
@Data
public class AcceptRefundReqDTO implements Serializable {

    @Schema(description = "旅通退款单号refund_bn，与channel_code构成退款永久幂等键",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "RT-REFUND-001")
    @NotBlank(message = "旅通退款单号不能为空")
    @Size(max = 128, message = "旅通退款单号不能超过 128 个字符")
    private String channelRefundNo;

    @Schema(description = "旅通支付流水号trade_id，与channel_code构成支付永久幂等键",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "RT-TRADE-001")
    @NotBlank(message = "旅通支付流水号不能为空")
    @Size(max = 128, message = "旅通支付流水号不能超过 128 个字符")
    private String tradeId;

    @Schema(description = "退款类型：CHANNEL_REFUND渠道发起退款，CANCEL_REFUND取消已支付流水触发的退款",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "CHANNEL_REFUND")
    @NotBlank(message = "退款类型不能为空")
    @Size(max = 32, message = "退款类型不能超过 32 个字符")
    private String refundType;

    @Schema(description = "渠道要求退款金额，单位为分",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "3000")
    @NotNull(message = "渠道退款金额不能为空")
    @Positive(message = "渠道退款金额必须大于 0")
    private Long channelRefundAmount;

    @Schema(description = "链路追踪标识，缺省由服务端生成", example = "trace-refund-001")
    @Size(max = 128, message = "链路追踪标识不能超过 128 个字符")
    private String traceId;
}
```

`QueryRefundStatusReqDTO.java`：

```java
package com.aifuli.module.supplychain.api.instore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 到店购退款状态查询请求（旅通接口⑥）。
 *
 * <p>非终态时服务端按原 refundCaseNo 权威查单兜底收敛。详见
 * {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#21-命令侧-dto-字段边界}。</p>
 */
@Schema(description = "到店购退款状态查询请求")
@Data
public class QueryRefundStatusReqDTO implements Serializable {

    @Schema(description = "旅通退款单号refund_bn，与channel_code构成退款永久幂等键",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "RT-REFUND-001")
    @NotBlank(message = "旅通退款单号不能为空")
    @Size(max = 128, message = "旅通退款单号不能超过 128 个字符")
    private String channelRefundNo;

    @Schema(description = "链路追踪标识，缺省由服务端生成", example = "trace-qr-001")
    @Size(max = 128, message = "链路追踪标识不能超过 128 个字符")
    private String traceId;
}
```

命令侧响应。`PaymentTicketRespDTO.java`：

```java
package com.aifuli.module.supplychain.api.instore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.ToString;

import java.io.Serializable;

/**
 * 到店购建单响应：受理结果与收银台地址。
 *
 * <p>cashierUrl 处理中可空，调用方必须按原 outTradeNo 查询收敛。详见
 * {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#22-命令侧响应-dto}。</p>
 */
@Schema(description = "到店购建单响应")
@Data
public class PaymentTicketRespDTO implements Serializable {

    @Schema(description = "供应链总订单号", example = "SC_1")
    private String orderNo;

    @Schema(description = "拉起采购商收银台的稳定外部交易号", example = "IPF_OTN")
    private String outTradeNo;

    @Schema(description = "采购商收银台地址，建单时返回；处理中可空，需按原 outTradeNo 查询")
    @ToString.Exclude
    private String cashierUrl;

    @Schema(description = "支付状态：0待支付，10支付处理中，20已支付，30支付失败，40已取消，90人工处理", example = "10")
    private Integer payStatus;
}
```

`PaymentStatusRespDTO.java`：

```java
package com.aifuli.module.supplychain.api.instore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 到店购支付状态响应：本地最新投影（触发权威查询时已收敛）。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#22-命令侧响应-dto}。</p>
 */
@Schema(description = "到店购支付状态响应")
@Data
public class PaymentStatusRespDTO implements Serializable {

    @Schema(description = "到店购支付流水稳定业务编号", example = "IPF_1")
    private String paymentFlowNo;

    @Schema(description = "拉起采购商收银台的稳定外部交易号", example = "IPF_OTN")
    private String outTradeNo;

    @Schema(description = "旅通支付流水号trade_id，与channel_code构成支付永久幂等键", example = "RT-TRADE-001")
    private String tradeId;

    @Schema(description = "支付状态：0待支付，10支付处理中，20已支付，30支付失败，40已取消，90人工处理", example = "20")
    private Integer payStatus;

    @Schema(description = "本笔支付流水金额，单位为分", example = "10000")
    private Long payAmount;

    @Schema(description = "采购商实际支付金额，单位为分；未支付为空", example = "10000")
    private Long buyerPaidAmount;

    @Schema(description = "采购商收银台支付单引用", example = "CASHIER_1")
    private String cashierReference;

    @Schema(description = "外部交易号快照", example = "TXN_1")
    private String externalTransactionId;
}
```

`CancelPaymentFlowRespDTO.java`：

```java
package com.aifuli.module.supplychain.api.instore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 到店购支付流水取消响应。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#22-命令侧响应-dto}。</p>
 */
@Schema(description = "到店购支付流水取消响应")
@Data
public class CancelPaymentFlowRespDTO implements Serializable {

    @Schema(description = "拉起采购商收银台的稳定外部交易号", example = "IPF_OTN")
    private String outTradeNo;

    @Schema(description = "取消转退款生成的中台退款稳定业务编号；未转退款为空", example = "IRC_C")
    private String refundCaseNo;

    @Schema(description = "是否已转全额退款（已支付流水取消）", example = "true")
    private Boolean convertedToRefund;

    @Schema(description = "是否抢占派发（转退款后立即外呼收银台）", example = "true")
    private Boolean claimedForDispatch;
}
```

`RefundAcceptanceRespDTO.java`：

```java
package com.aifuli.module.supplychain.api.instore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 到店购退款受理响应。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#22-命令侧响应-dto}。</p>
 */
@Schema(description = "到店购退款受理响应")
@Data
public class RefundAcceptanceRespDTO implements Serializable {

    @Schema(description = "中台退款稳定业务编号", example = "IRC_9")
    private String refundCaseNo;

    @Schema(description = "退款状态：10排队中，20采购商退款中", example = "20")
    private Integer refundStatus;

    @Schema(description = "是否抢占派发（本 Case 立即外呼收银台）", example = "true")
    private Boolean claimedForDispatch;
}
```

`RefundStatusRespDTO.java`：

```java
package com.aifuli.module.supplychain.api.instore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 到店购退款状态响应：本地最新投影（触发权威查询时已收敛）。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#22-命令侧响应-dto}。</p>
 */
@Schema(description = "到店购退款状态响应")
@Data
public class RefundStatusRespDTO implements Serializable {

    @Schema(description = "中台退款稳定业务编号", example = "IRC_9")
    private String refundCaseNo;

    @Schema(description = "旅通退款单号refund_bn，与channel_code构成退款永久幂等键", example = "RT-REFUND-001")
    private String channelRefundNo;

    @Schema(description = "退款类型：CHANNEL_REFUND渠道发起退款，CANCEL_REFUND取消已支付流水触发的退款",
            example = "CHANNEL_REFUND")
    private String refundType;

    @Schema(description = "退款状态：10排队中，20采购商退款中，30退款成功，40退款失败，60已完成，90人工处理",
            example = "30")
    private Integer status;

    @Schema(description = "渠道要求退款金额，单位为分", example = "3000")
    private Long channelRefundAmount;

    @Schema(description = "采购商实际退款金额，单位为分；未收敛为空", example = "3000")
    private Long buyerActualRefundAmount;
}
```

回调侧请求。`ConfirmPaymentReqDTO.java`：

```java
package com.aifuli.module.supplychain.api.instore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * SaaS 收银台支付成功回调请求：按 outTradeNo 收敛。
 *
 * <p>回调只提交原业务号与实际金额；归属按订单固化数据加载，回调参数不得改变归属。详见
 * {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#23-回调侧-dto-字段边界}。</p>
 */
@Schema(description = "到店购收银台支付成功回调请求")
@Data
public class ConfirmPaymentReqDTO implements Serializable {

    @Schema(description = "拉起采购商收银台的稳定外部交易号",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "IPF_OTN")
    @NotBlank(message = "外部交易号不能为空")
    @Size(max = 128, message = "外部交易号不能超过 128 个字符")
    private String outTradeNo;

    @Schema(description = "采购商实际支付金额，单位为分",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "10000")
    @NotNull(message = "实际支付金额不能为空")
    @Positive(message = "实际支付金额必须大于 0")
    private Long actualAmountCny;

    @Schema(description = "采购商收银台支付单引用", example = "CASHIER_1")
    @Size(max = 128, message = "收银台支付单引用不能超过 128 个字符")
    private String cashierOrderNo;

    @Schema(description = "收银台交易号", example = "TXN_1")
    @Size(max = 128, message = "收银台交易号不能超过 128 个字符")
    private String cashierTransactionNo;

    @Schema(description = "链路追踪标识，缺省由服务端生成", example = "trace-cb-001")
    @Size(max = 128, message = "链路追踪标识不能超过 128 个字符")
    private String traceId;
}
```

`ReportRefundResultReqDTO.java`：

```java
package com.aifuli.module.supplychain.api.instore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * SaaS 收银台退款结果回调请求：按 refundCaseNo 收敛。
 *
 * <p>实际退款金额缺失或与申请金额不一致时转人工且不返还采购商账户。详见
 * {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#23-回调侧-dto-字段边界}。</p>
 */
@Schema(description = "到店购收银台退款结果回调请求")
@Data
public class ReportRefundResultReqDTO implements Serializable {

    @Schema(description = "中台退款稳定业务编号",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "IRC_9")
    @NotBlank(message = "中台退款编号不能为空")
    @Size(max = 32, message = "中台退款编号不能超过 32 个字符")
    private String refundCaseNo;

    @Schema(description = "采购商实际退款金额，单位为分",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "3000")
    @NotNull(message = "实际退款金额不能为空")
    @Positive(message = "实际退款金额必须大于 0")
    private Long actualAmountCny;

    @Schema(description = "收银台退款单引用", example = "CRF_1")
    @Size(max = 128, message = "收银台退款单引用不能超过 128 个字符")
    private String cashierRefundReference;

    @Schema(description = "链路追踪标识，缺省由服务端生成", example = "trace-cb-001")
    @Size(max = 128, message = "链路追踪标识不能超过 128 个字符")
    private String traceId;
}
```

- [x] **Step 5: 运行契约测试确认 GREEN**

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-api -am \
  -Dtest=InstorePaymentCommandApiContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: BUILD SUCCESS，`InstorePaymentCommandApiContractTest` 全部 PASS。

- [x] **Step 6: 提交契约切片**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-api
git commit -m "$(cat <<'EOF'
feat(supply-chain): expose instore payment refund rpc contracts

新增 InstoreChannelCommandApi（旅通接口②③④⑤⑥）与
InstoreBuyerCashierCallbackApi（收银台支付/退款回调）及 12 个 Wire DTO，
字段注释与 013 DDL COMMENT 对齐；上下文派生字段禁止进入 Wire。
EOF
)"
```

---

### Task 2: ProjectContext 可信上下文扩展

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/security/ProjectCallContextAccessor.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/security/ProjectCallContextConfiguration.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/security/ProjectCallContextConfigurationTest.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreMemberEntryFlowIntegrationTest.java`（`StubContextAccessor` 增加新方法实现）

- [x] **Step 1: 写失败测试**

在 `ProjectCallContextConfigurationTest` 中：

1. `defaultAccessorFailsClosedWhenNoTrustedAccessorIsProvided` 增加 `assertBuyerAuthInvalid(accessor::requireProjectContext);`
2. 新增测试：

```java
    @Test
    void projectContextRejectsMissingProjectId() {
        assertThatThrownBy(() -> new ProjectCallContextAccessor.ProjectContext(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projectId");
    }
```

3. `CustomAccessorConfiguration` 的匿名类增加：

```java
                @Override
                public ProjectContext requireProjectContext() {
                    return new ProjectContext(1L);
                }
```

- [x] **Step 2: 运行确认 RED**

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest=ProjectCallContextConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: COMPILATION ERROR（`requireProjectContext`/`ProjectContext` 不存在）。

- [x] **Step 3: 实现 ProjectContext**

`ProjectCallContextAccessor.java` 在 `requireChannelContext()` 方法后新增方法与 record（放在接口内、`BuyerContext` 之前）：

```java
    /**
     * 获取仅项目维度的受信任上下文：SaaS 收银台回调等不携带采购商用户身份的调用使用。
     *
     * <p>详见 {@code docs/superpowers/specs/2026-08-21-supply-chain-buyer-project-design.md#54-projectcallcontext}。</p>
     *
     * @return 已认证的项目上下文
     */
    ProjectContext requireProjectContext();

    /**
     * 项目调用身份的不可变快照，仅包含项目维度，用于回调等无采购商用户身份的调用。
     */
    record ProjectContext(Long projectId) {

        public ProjectContext {
            if (Objects.isNull(projectId)) {
                throw new IllegalArgumentException("projectId 不能为空");
            }
        }
    }
```

`ProjectCallContextConfiguration.java` 的匿名类增加：

```java
            @Override
            public ProjectContext requireProjectContext() {
                throw exception(BUYER_AUTH_INVALID);
            }
```

`InstoreMemberEntryFlowIntegrationTest.java` 的 `StubContextAccessor` 增加（字段区 + 方法区，仿照 buyerContext 模式）：

```java
        private ProjectContext projectContext;

        void useProject(ProjectContext context) {
            this.projectContext = context;
        }

        @Override
        public ProjectContext requireProjectContext() {
            return Objects.requireNonNull(projectContext, "projectContext");
        }
```

并在该文件 import 区加入 `import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor.ProjectContext;`。

- [x] **Step 4: 运行确认 GREEN**

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest=ProjectCallContextConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS。

- [x] **Step 5: 提交**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/security \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/security \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreMemberEntryFlowIntegrationTest.java
git commit -m "$(cat <<'EOF'
feat(supply-chain): add project context to trusted call accessor

回调信任域只需 projectId：requireProjectContext 失败关闭默认实现
抛 BUYER_AUTH_INVALID，禁止从 DTO 或请求头猜测身份。
EOF
)"
```

---

### Task 3: 绑定只读解析 + Facade Wire 建单编排 + 查询视图包装

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/application/InstoreChannelUserBindingResolver.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/application/facade/InstoreChannelCommandFacade.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/application/facade/InstoreChannelCommandFacadeTest.java`

- [x] **Step 1: 写失败测试**

`InstoreChannelCommandFacadeTest`：

1. `@Mock` 区新增：

```java
    @Mock private InstoreChannelUserBindingResolver bindingResolver;
```

并增加 import：

```java
import com.aifuli.module.supplychain.border.instore.application.InstoreChannelUserBindingResolver;
```

2. `setUp()` 构造改为：

```java
        facade = new InstoreChannelCommandFacade(lifecycle, routeResolver, cashierPort,
                convergenceService, refundConvergenceService, bindingResolver);
```

3. 新增 Wire 建单测试：

```java
    @Test
    void wireCreatePaymentOrderResolvesBindingAndCurrentCashierRoute() {
        when(bindingResolver.requireActiveBinding(101L, "opaque-user", "RT_MART"))
                .thenReturn(new InstoreChannelUserBindingResolver.BindingSnapshot(
                        1L, 1001L, 101L, "AIFULI_MALL", "tenant-a", "opaque-user", "RT_MART", 0L));
        BuyerIntegrationRoute route = cashierRoute();
        when(routeResolver.resolveCurrent(101L, BuyerIntegrationCapabilityCode.CASHIER)).thenReturn(route);
        when(lifecycle.createPaymentOrder(any())).thenReturn(prepared(false, null,
                InstorePaymentFlowStatus.PENDING.getCode()));
        when(lifecycle.claimPaymentPreparation("IOP_1", 0L))
                .thenReturn(new InstoreChannelLifecycle.ClaimResult(true, 3L, 1L));
        when(cashierPort.createCashierOrder(any())).thenReturn(new BuyerCashierPort.CreateCashierOrderResult(
                BuyerCashierPort.CashierOrderStatus.CREATED, "CASHIER_1", "https://cashier/pay",
                10_000L, null, OffsetDateTime.parse("2026-08-21T01:00:00Z")));

        InstoreChannelCommandFacade.PaymentTicket ticket = facade.createPaymentOrder(
                new InstoreChannelCommandFacade.WireCreatePaymentCommand(
                        101L, "RT_MART", "ORD001", "TRADE001", "opaque-user", 10_000L, 1,
                        10_000L, "COMPANY", "MERCHANT1", "SHOP1", "大润发门店", "到店购", "trace-1"));

        ArgumentCaptor<InstoreChannelLifecycle.CreatePaymentOrderCommand> lifecycleCommand =
                ArgumentCaptor.forClass(InstoreChannelLifecycle.CreatePaymentOrderCommand.class);
        verify(lifecycle).createPaymentOrder(lifecycleCommand.capture());
        assertThat(lifecycleCommand.getValue().buyerId()).isEqualTo(1001L);
        assertThat(lifecycleCommand.getValue().projectId()).isEqualTo(101L);
        assertThat(lifecycleCommand.getValue().cashierProfileVersionId()).isEqualTo(202L);
        assertThat(lifecycleCommand.getValue().buyerUserId()).isEqualTo("opaque-user");
        assertThat(lifecycleCommand.getValue().userCode()).isEqualTo("opaque-user");
        ArgumentCaptor<BuyerCashierPort.CreateCashierOrderCommand> cashierCommand =
                ArgumentCaptor.forClass(BuyerCashierPort.CreateCashierOrderCommand.class);
        verify(cashierPort).createCashierOrder(cashierCommand.capture());
        assertThat(cashierCommand.getValue().route()).isSameAs(route);
        assertThat(ticket.cashierUrl()).isEqualTo("https://cashier/pay");
    }

    @Test
    void wireCreatePaymentOrderRejectsMissingBinding() {
        when(bindingResolver.requireActiveBinding(101L, "unknown-user", "RT_MART"))
                .thenThrow(new ServiceException(INSTORE_CHANNEL_USER_BINDING_MISSING));

        assertThatThrownBy(() -> facade.createPaymentOrder(
                new InstoreChannelCommandFacade.WireCreatePaymentCommand(
                        101L, "RT_MART", "ORD001", "TRADE001", "unknown-user", 10_000L, 1,
                        10_000L, "COMPANY", "MERCHANT1", null, null, null, "trace-1")))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(INSTORE_CHANNEL_USER_BINDING_MISSING.getCode());
        verify(lifecycle, never()).createPaymentOrder(any());
        verify(cashierPort, never()).createCashierOrder(any());
    }
```

增加 import：

```java
import com.aifuli.framework.common.exception.ServiceException;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_CHANNEL_USER_BINDING_MISSING;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

4. 查询方法返回类型改为 Facade 视图后更新两个既有测试：

```java
    @Test
    void channelPaymentQueryDelegatesToUnifiedConvergenceService() {
        when(convergenceService.queryAndConvergePayment(
                101L, "RT_MART", "TRADE001", "trace-query")).thenReturn(new InstoreChannelLifecycle.PaymentStatusView(
                "IPF_1", "OTN_1", "TRADE001", InstorePaymentFlowStatus.PAID.getCode(),
                10_000L, 10_000L, "CASHIER_1", "TXN_1"));

        InstoreChannelCommandFacade.PaymentStatusView result = facade.queryAndConvergePayment(
                new InstoreChannelCommandFacade.QueryPaymentCommand(101L, "RT_MART", "TRADE001", "trace-query"));

        assertThat(result.paymentFlowNo()).isEqualTo("IPF_1");
        assertThat(result.outTradeNo()).isEqualTo("OTN_1");
        assertThat(result.payStatus()).isEqualTo(InstorePaymentFlowStatus.PAID.getCode());
        assertThat(result.buyerPaidAmount()).isEqualTo(10_000L);
    }

    @Test
    void queryAndConvergeRefundDelegatesToConvergenceService() {
        when(refundConvergenceService.queryAndConvergeRefund(1L, "RT_MART", "R9", "trace-1"))
                .thenReturn(new InstoreChannelLifecycle.RefundStatusView(
                        "IRC_9", "R9", "CHANNEL_REFUND",
                        InstoreRefundStatus.BUYER_REFUNDING.getCode(), 300L, null));

        InstoreChannelCommandFacade.RefundStatusView result = facade.queryAndConvergeRefund(
                new InstoreChannelCommandFacade.QueryRefundCommand(1L, "RT_MART", "R9", "trace-1"));

        assertThat(result.refundCaseNo()).isEqualTo("IRC_9");
        assertThat(result.status()).isEqualTo(InstoreRefundStatus.BUYER_REFUNDING.getCode());
        assertThat(result.channelRefundAmount()).isEqualTo(300L);
    }
```

- [x] **Step 2: 运行确认 RED**

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest=InstoreChannelCommandFacadeTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: COMPILATION ERROR（`requireActiveBinding`、`WireCreatePaymentCommand`、Facade 视图 record 不存在）。

- [x] **Step 3: 实现**

3a. `InstoreChannelUserBindingResolver.java` 在 `resolveActiveById` 方法后新增：

```java
    /**
     * 按项目会员三元组只读解析启用绑定，供 Wire 层建单编排获得采购商归属。
     *
     * <p>只读不创建、不刷新路由快照；绑定缺失或停用按渠道语义抛绑定缺失。
     * 详见 {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#5-facade-wire-编排方案-a}。</p>
     *
     * @param projectId 采购项目内部主键
     * @param buyerUserId 旅通会员编号user_code，不透明字符串
     * @param channelCode 到店购渠道编码
     * @return 启用绑定快照
     */
    public BindingSnapshot requireActiveBinding(Long projectId, String buyerUserId, String channelCode) {
        log.info("[INSTORE][binding] requireActiveBinding 入口 projectId={}, channelCode={}",
                projectId, channelCode);
        InstoreChannelUserBindingDO binding = bindingMapper.selectByProjectUser(
                projectId, buyerUserId, channelCode);
        if (binding == null || binding.getStatus() == null || binding.getStatus() != STATUS_ACTIVE) {
            log.warn("[INSTORE][binding] 启用绑定不存在 projectId={}, channelCode={}", projectId, channelCode);
            throw exception(INSTORE_CHANNEL_USER_BINDING_MISSING);
        }
        return snapshot(binding);
    }
```

3b. `InstoreChannelCommandFacade.java`：

1. import 区新增：

```java
import com.aifuli.module.supplychain.border.instore.application.InstoreChannelUserBindingResolver;
```

2. `@RequiredArgsConstructor` 字段区新增（放在 `refundConvergenceService` 之后）：

```java
    private final InstoreChannelUserBindingResolver bindingResolver;
```

3. 在 `createPayment` 方法前新增 Wire 建单方法：

```java
    /**
     * Wire 层建单编排：先解析稳定绑定获得采购商归属，再按项目解析当前 CASHIER 档案版本，
     * 组装既有 {@link PreparePaymentCommand} 委托 {@link #createPayment}。
     *
     * <p>projectId/channelCode 来自可信渠道上下文；绑定缺失或停用抛绑定缺失，
     * 当前档案版本未发布抛集成档案错误。详见
     * {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#5-facade-wire-编排方案-a}。</p>
     */
    public PaymentTicket createPaymentOrder(WireCreatePaymentCommand command) {
        Objects.requireNonNull(command, "command");
        log.info("[INSTORE][facade] createPaymentOrder 入口 projectId={}, channelCode={}, channelOrderId={}, tradeId={}, payAmount={}, traceId={}",
                command.projectId(), command.channelCode(), command.channelOrderId(),
                command.tradeId(), command.payAmount(), command.traceId());
        InstoreChannelUserBindingResolver.BindingSnapshot binding = bindingResolver.requireActiveBinding(
                command.projectId(), command.userCode(), command.channelCode());
        BuyerIntegrationRoute cashierRoute = routeResolver.resolveCurrent(
                command.projectId(), BuyerIntegrationCapabilityCode.CASHIER);
        return createPayment(new PreparePaymentCommand(
                command.channelCode(), command.channelOrderId(), command.tradeId(),
                binding.buyerId(), command.projectId(), cashierRoute.profileVersionId(),
                command.userCode(), command.userCode(), command.payAmount(), command.orderType(),
                command.totalAmount(), command.companyCode(), command.externalMerchantId(),
                command.shopCode(), command.shopName(), command.subject(), command.traceId()));
    }

    /** Wire 层建单命令：projectId/channelCode 来自可信渠道上下文，其余字段来自渠道 DTO。 */
    public record WireCreatePaymentCommand(Long projectId, String channelCode, String channelOrderId,
                                           String tradeId, String userCode, long payAmount, int orderType,
                                           long totalAmount, String companyCode, String externalMerchantId,
                                           String shopCode, String shopName, String subject, String traceId) {
    }
```

4. 替换 `queryAndConvergePayment`（当前 L107-111）：

```java
    public PaymentStatusView queryAndConvergePayment(QueryPaymentCommand command) {
        Objects.requireNonNull(command, "command");
        InstoreChannelLifecycle.PaymentStatusView view = convergenceService.queryAndConvergePayment(
                command.projectId(), command.channelCode(), command.tradeId(), command.traceId());
        return new PaymentStatusView(view.paymentFlowNo(), view.outTradeNo(), view.tradeId(),
                view.payStatus(), view.payAmount(), view.buyerPaidAmount(),
                view.cashierReference(), view.externalTransactionId());
    }
```

5. 替换 `queryAndConvergeRefund`（当前 L156-160）：

```java
    public RefundStatusView queryAndConvergeRefund(QueryRefundCommand command) {
        Objects.requireNonNull(command, "command");
        InstoreChannelLifecycle.RefundStatusView view = refundConvergenceService.queryAndConvergeRefund(
                command.projectId(), command.channelCode(), command.channelRefundNo(), command.traceId());
        return new RefundStatusView(view.refundCaseNo(), view.channelRefundNo(), view.refundType(),
                view.status(), view.channelRefundAmount(), view.buyerActualRefundAmount());
    }
```

6. 在既有 record 区（`PaymentTicket` 等附近）新增两个视图 record：

```java
    /** 支付状态只读投影，字段语义与 sc_instore_payment_flow 列一致；供 RPC 实现层映射 Wire 响应。 */
    public record PaymentStatusView(String paymentFlowNo, String outTradeNo, String tradeId,
                                    short payStatus, long payAmount, Long buyerPaidAmount,
                                    String cashierReference, String externalTransactionId) {
    }

    /** 退款状态只读投影，字段语义与 sc_instore_refund_case 列一致；供 RPC 实现层映射 Wire 响应。 */
    public record RefundStatusView(String refundCaseNo, String channelRefundNo, String refundType,
                                   short status, long channelRefundAmount, Long buyerActualRefundAmount) {
    }
```

- [x] **Step 4: 运行确认 GREEN**

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest=InstoreChannelCommandFacadeTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS（既有 8 个测试 + 新增 2 个）。

- [x] **Step 5: 提交**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/application/InstoreChannelUserBindingResolver.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/application/facade/InstoreChannelCommandFacade.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/application/facade/InstoreChannelCommandFacadeTest.java
git commit -m "$(cat <<'EOF'
feat(supply-chain): orchestrate wire payment order in command facade

Wire 建单先解析稳定绑定拿 buyerId、再按项目取当前 CASHIER 档案版本后
委托既有 createPayment；两个查询方法返回值包装为 Facade 自有视图，
使 RPC 层不依赖 border 包。
EOF
)"
```

---

### Task 4: 两个 ApiImpl

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/interfaceapi/rpc/InstoreChannelCommandApiImpl.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/interfaceapi/rpc/InstoreBuyerCashierCallbackApiImpl.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/interfaceapi/rpc/InstoreChannelCommandApiImplTest.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/interfaceapi/rpc/InstoreBuyerCashierCallbackApiImplTest.java`

- [x] **Step 1: 写失败测试**

`InstoreChannelCommandApiImplTest.java`：

```java
package com.aifuli.module.supplychain.interfaceapi.rpc;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.instore.InstoreChannelCommandApi;
import com.aifuli.module.supplychain.api.instore.dto.AcceptRefundReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.CancelPaymentFlowReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.CancelPaymentFlowRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.CreatePaymentOrderReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.PaymentStatusRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.PaymentTicketRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.QueryPaymentStatusReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.QueryRefundStatusReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.RefundAcceptanceRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.RefundStatusRespDTO;
import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.application.facade.InstoreChannelCommandFacade;
import com.aifuli.module.supplychain.border.instore.domain.InstorePaymentFlowStatus;
import com.aifuli.module.supplychain.border.instore.domain.InstoreRefundStatus;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor.ChannelContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 到店购支付/退款命令 RPC 实现单测：上下文是唯一身份来源，Wire 映射逐字段对齐。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#3-interface-adapter-层}。</p>
 */
@ExtendWith(MockitoExtension.class)
class InstoreChannelCommandApiImplTest {

    @Mock private ProjectCallContextAccessor contextAccessor;
    @Mock private InstoreChannelCommandFacade facade;

    private InstoreChannelCommandApiImpl api;

    @BeforeEach
    void setUp() {
        api = new InstoreChannelCommandApiImpl(contextAccessor, facade);
    }

    @Test
    void createPaymentOrderMapsAllFieldsAndDerivesIdentityFromChannelContext() {
        when(contextAccessor.requireChannelContext()).thenReturn(new ChannelContext(101L, "RT_MART"));
        when(facade.createPaymentOrder(any())).thenReturn(new InstoreChannelCommandFacade.PaymentTicket(
                "SC_1", "IPF_OTN", "https://cashier/pay", InstorePaymentFlowStatus.PROCESSING.getCode()));
        CreatePaymentOrderReqDTO request = new CreatePaymentOrderReqDTO();
        request.setChannelOrderId("ORD001");
        request.setTradeId("TRADE001");
        request.setUserCode("opaque-user");
        request.setPayAmount(10_000L);
        request.setOrderType(1);
        request.setTotalAmount(10_000L);
        request.setCompanyCode("COMPANY");
        request.setExternalMerchantId("MERCHANT1");
        request.setShopCode("SHOP1");
        request.setShopName("大润发门店");
        request.setSubject("到店购");
        request.setTraceId("trace-1");

        CommonResult<PaymentTicketRespDTO> result = api.createPaymentOrder(request);

        ArgumentCaptor<InstoreChannelCommandFacade.WireCreatePaymentCommand> captor =
                ArgumentCaptor.forClass(InstoreChannelCommandFacade.WireCreatePaymentCommand.class);
        verify(facade).createPaymentOrder(captor.capture());
        assertThat(captor.getValue().projectId()).isEqualTo(101L);
        assertThat(captor.getValue().channelCode()).isEqualTo("RT_MART");
        assertThat(captor.getValue().channelOrderId()).isEqualTo("ORD001");
        assertThat(captor.getValue().tradeId()).isEqualTo("TRADE001");
        assertThat(captor.getValue().userCode()).isEqualTo("opaque-user");
        assertThat(captor.getValue().payAmount()).isEqualTo(10_000L);
        assertThat(captor.getValue().orderType()).isEqualTo(1);
        assertThat(captor.getValue().totalAmount()).isEqualTo(10_000L);
        assertThat(captor.getValue().companyCode()).isEqualTo("COMPANY");
        assertThat(captor.getValue().externalMerchantId()).isEqualTo("MERCHANT1");
        assertThat(captor.getValue().shopCode()).isEqualTo("SHOP1");
        assertThat(captor.getValue().shopName()).isEqualTo("大润发门店");
        assertThat(captor.getValue().subject()).isEqualTo("到店购");
        assertThat(captor.getValue().traceId()).isEqualTo("trace-1");
        assertThat(result.getData().getOrderNo()).isEqualTo("SC_1");
        assertThat(result.getData().getOutTradeNo()).isEqualTo("IPF_OTN");
        assertThat(result.getData().getCashierUrl()).isEqualTo("https://cashier/pay");
        assertThat(result.getData().getPayStatus())
                .isEqualTo((int) InstorePaymentFlowStatus.PROCESSING.getCode());
    }

    @Test
    void createPaymentOrderGeneratesTraceIdWhenBlank() {
        when(contextAccessor.requireChannelContext()).thenReturn(new ChannelContext(101L, "RT_MART"));
        when(facade.createPaymentOrder(any())).thenReturn(new InstoreChannelCommandFacade.PaymentTicket(
                "SC_1", "IPF_OTN", null, InstorePaymentFlowStatus.PENDING.getCode()));
        CreatePaymentOrderReqDTO request = new CreatePaymentOrderReqDTO();
        request.setChannelOrderId("ORD001");
        request.setTradeId("TRADE001");
        request.setUserCode("opaque-user");
        request.setPayAmount(10_000L);
        request.setOrderType(1);
        request.setTotalAmount(10_000L);
        request.setCompanyCode("COMPANY");
        request.setExternalMerchantId("MERCHANT1");

        api.createPaymentOrder(request);

        ArgumentCaptor<InstoreChannelCommandFacade.WireCreatePaymentCommand> captor =
                ArgumentCaptor.forClass(InstoreChannelCommandFacade.WireCreatePaymentCommand.class);
        verify(facade).createPaymentOrder(captor.capture());
        assertThat(captor.getValue().traceId()).isNotBlank();
    }

    @Test
    void queryPaymentStatusMapsFacadeViewToResponse() {
        when(contextAccessor.requireChannelContext()).thenReturn(new ChannelContext(101L, "RT_MART"));
        when(facade.queryAndConvergePayment(any())).thenReturn(new InstoreChannelCommandFacade.PaymentStatusView(
                "IPF_1", "OTN_1", "TRADE001", InstorePaymentFlowStatus.PAID.getCode(),
                10_000L, 10_000L, "CASHIER_1", "TXN_1"));
        QueryPaymentStatusReqDTO request = new QueryPaymentStatusReqDTO();
        request.setTradeId("TRADE001");
        request.setTraceId("trace-q");

        CommonResult<PaymentStatusRespDTO> result = api.queryPaymentStatus(request);

        ArgumentCaptor<InstoreChannelCommandFacade.QueryPaymentCommand> captor =
                ArgumentCaptor.forClass(InstoreChannelCommandFacade.QueryPaymentCommand.class);
        verify(facade).queryAndConvergePayment(captor.capture());
        assertThat(captor.getValue().projectId()).isEqualTo(101L);
        assertThat(captor.getValue().channelCode()).isEqualTo("RT_MART");
        assertThat(captor.getValue().tradeId()).isEqualTo("TRADE001");
        assertThat(result.getData().getPaymentFlowNo()).isEqualTo("IPF_1");
        assertThat(result.getData().getBuyerPaidAmount()).isEqualTo(10_000L);
        assertThat(result.getData().getPayStatus()).isEqualTo((int) InstorePaymentFlowStatus.PAID.getCode());
    }

    @Test
    void cancelPaymentFlowMapsCancelTicket() {
        when(contextAccessor.requireChannelContext()).thenReturn(new ChannelContext(101L, "RT_MART"));
        when(facade.cancelPaymentFlow(any())).thenReturn(new InstoreChannelCommandFacade.CancelPaymentTicket(
                "OT1", "IRC_C", true, true));
        CancelPaymentFlowReqDTO request = new CancelPaymentFlowReqDTO();
        request.setTradeId("TRADE001");

        CommonResult<CancelPaymentFlowRespDTO> result = api.cancelPaymentFlow(request);

        ArgumentCaptor<InstoreChannelCommandFacade.CancelPaymentCommand> captor =
                ArgumentCaptor.forClass(InstoreChannelCommandFacade.CancelPaymentCommand.class);
        verify(facade).cancelPaymentFlow(captor.capture());
        assertThat(captor.getValue().channelCode()).isEqualTo("RT_MART");
        assertThat(result.getData().getRefundCaseNo()).isEqualTo("IRC_C");
        assertThat(result.getData().getConvertedToRefund()).isTrue();
    }

    @Test
    void acceptRefundMapsRefundAcceptance() {
        when(contextAccessor.requireChannelContext()).thenReturn(new ChannelContext(101L, "RT_MART"));
        when(facade.acceptRefund(any())).thenReturn(new InstoreChannelCommandFacade.RefundAcceptance(
                "IRC_9", InstoreRefundStatus.QUEUED.getCode(), false));
        AcceptRefundReqDTO request = new AcceptRefundReqDTO();
        request.setChannelRefundNo("R9");
        request.setTradeId("TRADE001");
        request.setRefundType("CHANNEL_REFUND");
        request.setChannelRefundAmount(300L);

        CommonResult<RefundAcceptanceRespDTO> result = api.acceptRefund(request);

        ArgumentCaptor<InstoreChannelCommandFacade.AcceptRefundCommand> captor =
                ArgumentCaptor.forClass(InstoreChannelCommandFacade.AcceptRefundCommand.class);
        verify(facade).acceptRefund(captor.capture());
        assertThat(captor.getValue().channelCode()).isEqualTo("RT_MART");
        assertThat(captor.getValue().channelRefundNo()).isEqualTo("R9");
        assertThat(captor.getValue().channelRefundAmount()).isEqualTo(300L);
        assertThat(result.getData().getRefundStatus()).isEqualTo((int) InstoreRefundStatus.QUEUED.getCode());
        assertThat(result.getData().getClaimedForDispatch()).isFalse();
    }

    @Test
    void queryRefundStatusMapsRefundView() {
        when(contextAccessor.requireChannelContext()).thenReturn(new ChannelContext(101L, "RT_MART"));
        when(facade.queryAndConvergeRefund(any())).thenReturn(new InstoreChannelCommandFacade.RefundStatusView(
                "IRC_9", "R9", "CHANNEL_REFUND", InstoreRefundStatus.SUCCESS.getCode(), 300L, 300L));
        QueryRefundStatusReqDTO request = new QueryRefundStatusReqDTO();
        request.setChannelRefundNo("R9");

        CommonResult<RefundStatusRespDTO> result = api.queryRefundStatus(request);

        ArgumentCaptor<InstoreChannelCommandFacade.QueryRefundCommand> captor =
                ArgumentCaptor.forClass(InstoreChannelCommandFacade.QueryRefundCommand.class);
        verify(facade).queryAndConvergeRefund(captor.capture());
        assertThat(captor.getValue().projectId()).isEqualTo(101L);
        assertThat(result.getData().getStatus()).isEqualTo((int) InstoreRefundStatus.SUCCESS.getCode());
        assertThat(result.getData().getBuyerActualRefundAmount()).isEqualTo(300L);
    }

    @Test
    void rejectsAllEndpointsWhenChannelContextMissing() {
        when(contextAccessor.requireChannelContext()).thenThrow(new ServiceException(BUYER_AUTH_INVALID));

        assertThatThrownBy(() -> api.createPaymentOrder(new CreatePaymentOrderReqDTO()))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> api.queryPaymentStatus(new QueryPaymentStatusReqDTO()))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> api.cancelPaymentFlow(new CancelPaymentFlowReqDTO()))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> api.acceptRefund(new AcceptRefundReqDTO()))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> api.queryRefundStatus(new QueryRefundStatusReqDTO()))
                .isInstanceOf(ServiceException.class);
    }
}
```

`InstoreBuyerCashierCallbackApiImplTest.java`：

```java
package com.aifuli.module.supplychain.interfaceapi.rpc;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.application.facade.InstoreBuyerCashierCallbackFacade;
import com.aifuli.module.supplychain.api.instore.dto.ConfirmPaymentReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.ReportRefundResultReqDTO;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor.ProjectContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 到店购收银台回调 RPC 实现单测：ProjectContext 是唯一身份来源，回调参数不改归属。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#3-interface-adapter-层}。</p>
 */
@ExtendWith(MockitoExtension.class)
class InstoreBuyerCashierCallbackApiImplTest {

    @Mock private ProjectCallContextAccessor contextAccessor;
    @Mock private InstoreBuyerCashierCallbackFacade facade;

    private InstoreBuyerCashierCallbackApiImpl api;

    @BeforeEach
    void setUp() {
        api = new InstoreBuyerCashierCallbackApiImpl(contextAccessor, facade);
    }

    @Test
    void confirmPaymentMapsCommandFromProjectContext() {
        when(contextAccessor.requireProjectContext()).thenReturn(new ProjectContext(101L));
        ConfirmPaymentReqDTO request = new ConfirmPaymentReqDTO();
        request.setOutTradeNo("IPF_OTN");
        request.setActualAmountCny(10_000L);
        request.setCashierOrderNo("CASHIER_1");
        request.setCashierTransactionNo("TXN_1");
        request.setTraceId("trace-cb");

        api.confirmPayment(request);

        ArgumentCaptor<InstoreBuyerCashierCallbackFacade.PaymentCallbackCommand> captor =
                ArgumentCaptor.forClass(InstoreBuyerCashierCallbackFacade.PaymentCallbackCommand.class);
        verify(facade).confirmPayment(captor.capture());
        assertThat(captor.getValue().projectId()).isEqualTo(101L);
        assertThat(captor.getValue().outTradeNo()).isEqualTo("IPF_OTN");
        assertThat(captor.getValue().actualAmountCny()).isEqualTo(10_000L);
        assertThat(captor.getValue().cashierOrderNo()).isEqualTo("CASHIER_1");
        assertThat(captor.getValue().cashierTransactionNo()).isEqualTo("TXN_1");
        assertThat(captor.getValue().traceId()).isEqualTo("trace-cb");
    }

    @Test
    void reportRefundResultMapsCommandFromProjectContext() {
        when(contextAccessor.requireProjectContext()).thenReturn(new ProjectContext(101L));
        ReportRefundResultReqDTO request = new ReportRefundResultReqDTO();
        request.setRefundCaseNo("IRC_9");
        request.setActualAmountCny(300L);
        request.setCashierRefundReference("CRF_1");

        api.reportRefundResult(request);

        ArgumentCaptor<InstoreBuyerCashierCallbackFacade.RefundCallbackCommand> captor =
                ArgumentCaptor.forClass(InstoreBuyerCashierCallbackFacade.RefundCallbackCommand.class);
        verify(facade).confirmRefund(captor.capture());
        assertThat(captor.getValue().projectId()).isEqualTo(101L);
        assertThat(captor.getValue().refundCaseNo()).isEqualTo("IRC_9");
        assertThat(captor.getValue().actualAmountCny()).isEqualTo(300L);
        assertThat(captor.getValue().cashierRefundReference()).isEqualTo("CRF_1");
        assertThat(captor.getValue().traceId()).isNotBlank();
    }

    @Test
    void rejectsCallbacksWhenProjectContextMissing() {
        when(contextAccessor.requireProjectContext()).thenThrow(new ServiceException(BUYER_AUTH_INVALID));

        assertThatThrownBy(() -> api.confirmPayment(new ConfirmPaymentReqDTO()))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> api.reportRefundResult(new ReportRefundResultReqDTO()))
                .isInstanceOf(ServiceException.class);
    }
}
```

- [x] **Step 2: 运行确认 RED**

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest=InstoreChannelCommandApiImplTest,InstoreBuyerCashierCallbackApiImplTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: COMPILATION ERROR（ApiImpl 不存在）。

- [x] **Step 3: 实现两个 ApiImpl**

`InstoreChannelCommandApiImpl.java`：

```java
package com.aifuli.module.supplychain.interfaceapi.rpc;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.instore.InstoreChannelCommandApi;
import com.aifuli.module.supplychain.api.instore.dto.AcceptRefundReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.CancelPaymentFlowReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.CancelPaymentFlowRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.CreatePaymentOrderReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.PaymentStatusRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.PaymentTicketRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.QueryPaymentStatusReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.QueryRefundStatusReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.RefundAcceptanceRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.RefundStatusRespDTO;
import com.aifuli.module.supplychain.application.facade.InstoreChannelCommandFacade;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

import static com.aifuli.framework.common.pojo.CommonResult.success;

/**
 * 到店购支付/退款命令 RPC 实现（组合部署本地 @Primary；拆分部署由 Feign 走 ApiImpl）。
 *
 * <p>只做三件事：取可信渠道上下文、出入口日志、Wire DTO 与 Facade Command 映射；
 * 身份（projectId/channelCode）只能来自可信上下文，异常由全局处理转 CommonResult。详见
 * {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#3-interface-adapter-层}。</p>
 */
@Slf4j
@RestController
@Primary
@Validated
@RequiredArgsConstructor
public class InstoreChannelCommandApiImpl implements InstoreChannelCommandApi {

    private final ProjectCallContextAccessor contextAccessor;
    private final InstoreChannelCommandFacade facade;

    @Override
    public CommonResult<PaymentTicketRespDTO> createPaymentOrder(CreatePaymentOrderReqDTO request) {
        ProjectCallContextAccessor.ChannelContext context = contextAccessor.requireChannelContext();
        String traceId = traceIdOrGenerate(request.getTraceId());
        log.info("[INSTORE][rpc] createPaymentOrder 入口 projectId={}, channelCode={}, channelOrderId={}, tradeId={}, payAmount={}, traceId={}",
                context.projectId(), context.channelCode(), request.getChannelOrderId(),
                request.getTradeId(), request.getPayAmount(), traceId);
        InstoreChannelCommandFacade.PaymentTicket ticket = facade.createPaymentOrder(
                new InstoreChannelCommandFacade.WireCreatePaymentCommand(
                        context.projectId(), context.channelCode(), request.getChannelOrderId(),
                        request.getTradeId(), request.getUserCode(), request.getPayAmount(),
                        request.getOrderType(), request.getTotalAmount(), request.getCompanyCode(),
                        request.getExternalMerchantId(), request.getShopCode(), request.getShopName(),
                        request.getSubject(), traceId));
        PaymentTicketRespDTO response = new PaymentTicketRespDTO();
        response.setOrderNo(ticket.orderNo());
        response.setOutTradeNo(ticket.outTradeNo());
        response.setCashierUrl(ticket.cashierUrl());
        response.setPayStatus((int) ticket.payStatus());
        log.info("[INSTORE][rpc] createPaymentOrder 出口 projectId={}, channelCode={}, orderNo={}, outTradeNo={}, payStatus={}, hasCashierUrl={}",
                context.projectId(), context.channelCode(), ticket.orderNo(), ticket.outTradeNo(),
                ticket.payStatus(), Objects.nonNull(ticket.cashierUrl()));
        return success(response);
    }

    @Override
    public CommonResult<PaymentStatusRespDTO> queryPaymentStatus(QueryPaymentStatusReqDTO request) {
        ProjectCallContextAccessor.ChannelContext context = contextAccessor.requireChannelContext();
        String traceId = traceIdOrGenerate(request.getTraceId());
        log.info("[INSTORE][rpc] queryPaymentStatus 入口 projectId={}, channelCode={}, tradeId={}, traceId={}",
                context.projectId(), context.channelCode(), request.getTradeId(), traceId);
        InstoreChannelCommandFacade.PaymentStatusView view = facade.queryAndConvergePayment(
                new InstoreChannelCommandFacade.QueryPaymentCommand(
                        context.projectId(), context.channelCode(), request.getTradeId(), traceId));
        PaymentStatusRespDTO response = new PaymentStatusRespDTO();
        response.setPaymentFlowNo(view.paymentFlowNo());
        response.setOutTradeNo(view.outTradeNo());
        response.setTradeId(view.tradeId());
        response.setPayStatus((int) view.payStatus());
        response.setPayAmount(view.payAmount());
        response.setBuyerPaidAmount(view.buyerPaidAmount());
        response.setCashierReference(view.cashierReference());
        response.setExternalTransactionId(view.externalTransactionId());
        log.info("[INSTORE][rpc] queryPaymentStatus 出口 projectId={}, channelCode={}, tradeId={}, payStatus={}, buyerPaidAmount={}",
                context.projectId(), context.channelCode(), request.getTradeId(),
                view.payStatus(), view.buyerPaidAmount());
        return success(response);
    }

    @Override
    public CommonResult<CancelPaymentFlowRespDTO> cancelPaymentFlow(CancelPaymentFlowReqDTO request) {
        ProjectCallContextAccessor.ChannelContext context = contextAccessor.requireChannelContext();
        String traceId = traceIdOrGenerate(request.getTraceId());
        log.info("[INSTORE][rpc] cancelPaymentFlow 入口 projectId={}, channelCode={}, tradeId={}, traceId={}",
                context.projectId(), context.channelCode(), request.getTradeId(), traceId);
        InstoreChannelCommandFacade.CancelPaymentTicket ticket = facade.cancelPaymentFlow(
                new InstoreChannelCommandFacade.CancelPaymentCommand(
                        context.channelCode(), request.getTradeId(), traceId));
        CancelPaymentFlowRespDTO response = new CancelPaymentFlowRespDTO();
        response.setOutTradeNo(ticket.outTradeNo());
        response.setRefundCaseNo(ticket.refundCaseNo());
        response.setConvertedToRefund(ticket.convertedToRefund());
        response.setClaimedForDispatch(ticket.claimedForDispatch());
        log.info("[INSTORE][rpc] cancelPaymentFlow 出口 projectId={}, channelCode={}, tradeId={}, convertedToRefund={}, claimedForDispatch={}",
                context.projectId(), context.channelCode(), request.getTradeId(),
                ticket.convertedToRefund(), ticket.claimedForDispatch());
        return success(response);
    }

    @Override
    public CommonResult<RefundAcceptanceRespDTO> acceptRefund(AcceptRefundReqDTO request) {
        ProjectCallContextAccessor.ChannelContext context = contextAccessor.requireChannelContext();
        String traceId = traceIdOrGenerate(request.getTraceId());
        log.info("[INSTORE][rpc] acceptRefund 入口 projectId={}, channelCode={}, channelRefundNo={}, tradeId={}, channelRefundAmount={}, traceId={}",
                context.projectId(), context.channelCode(), request.getChannelRefundNo(),
                request.getTradeId(), request.getChannelRefundAmount(), traceId);
        InstoreChannelCommandFacade.RefundAcceptance acceptance = facade.acceptRefund(
                new InstoreChannelCommandFacade.AcceptRefundCommand(
                        context.channelCode(), request.getChannelRefundNo(), request.getTradeId(),
                        request.getRefundType(), request.getChannelRefundAmount(), traceId));
        RefundAcceptanceRespDTO response = new RefundAcceptanceRespDTO();
        response.setRefundCaseNo(acceptance.refundCaseNo());
        response.setRefundStatus((int) acceptance.refundStatus());
        response.setClaimedForDispatch(acceptance.claimedForDispatch());
        log.info("[INSTORE][rpc] acceptRefund 出口 projectId={}, channelCode={}, refundCaseNo={}, refundStatus={}, claimedForDispatch={}",
                context.projectId(), context.channelCode(), acceptance.refundCaseNo(),
                acceptance.refundStatus(), acceptance.claimedForDispatch());
        return success(response);
    }

    @Override
    public CommonResult<RefundStatusRespDTO> queryRefundStatus(QueryRefundStatusReqDTO request) {
        ProjectCallContextAccessor.ChannelContext context = contextAccessor.requireChannelContext();
        String traceId = traceIdOrGenerate(request.getTraceId());
        log.info("[INSTORE][rpc] queryRefundStatus 入口 projectId={}, channelCode={}, channelRefundNo={}, traceId={}",
                context.projectId(), context.channelCode(), request.getChannelRefundNo(), traceId);
        InstoreChannelCommandFacade.RefundStatusView view = facade.queryAndConvergeRefund(
                new InstoreChannelCommandFacade.QueryRefundCommand(
                        context.projectId(), context.channelCode(),
                        request.getChannelRefundNo(), traceId));
        RefundStatusRespDTO response = new RefundStatusRespDTO();
        response.setRefundCaseNo(view.refundCaseNo());
        response.setChannelRefundNo(view.channelRefundNo());
        response.setRefundType(view.refundType());
        response.setStatus((int) view.status());
        response.setChannelRefundAmount(view.channelRefundAmount());
        response.setBuyerActualRefundAmount(view.buyerActualRefundAmount());
        log.info("[INSTORE][rpc] queryRefundStatus 出口 projectId={}, channelCode={}, refundCaseNo={}, status={}, buyerActualRefundAmount={}",
                context.projectId(), context.channelCode(), view.refundCaseNo(),
                view.status(), view.buyerActualRefundAmount());
        return success(response);
    }

    private static String traceIdOrGenerate(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return traceId;
    }
}
```

`InstoreBuyerCashierCallbackApiImpl.java`：

```java
package com.aifuli.module.supplychain.interfaceapi.rpc;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.instore.InstoreBuyerCashierCallbackApi;
import com.aifuli.module.supplychain.api.instore.dto.ConfirmPaymentReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.ReportRefundResultReqDTO;
import com.aifuli.module.supplychain.application.facade.InstoreBuyerCashierCallbackFacade;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.aifuli.framework.common.pojo.CommonResult.success;

/**
 * 到店购收银台回调 RPC 实现（组合部署本地 @Primary；拆分部署由 Feign 走 ApiImpl）。
 *
 * <p>信任域为 SaaS 收银台 Principal：projectId 只来自可信 ProjectContext，
 * 归属按 outTradeNo/refundCaseNo 加载订单固化数据，回调参数不得改变归属。详见
 * {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#3-interface-adapter-层}。</p>
 */
@Slf4j
@RestController
@Primary
@Validated
@RequiredArgsConstructor
public class InstoreBuyerCashierCallbackApiImpl implements InstoreBuyerCashierCallbackApi {

    private final ProjectCallContextAccessor contextAccessor;
    private final InstoreBuyerCashierCallbackFacade facade;

    @Override
    public CommonResult<Void> confirmPayment(ConfirmPaymentReqDTO request) {
        ProjectCallContextAccessor.ProjectContext context = contextAccessor.requireProjectContext();
        String traceId = traceIdOrGenerate(request.getTraceId());
        log.info("[INSTORE][rpc] confirmPayment 入口 projectId={}, outTradeNo={}, actualAmountCny={}, traceId={}",
                context.projectId(), request.getOutTradeNo(), request.getActualAmountCny(), traceId);
        facade.confirmPayment(new InstoreBuyerCashierCallbackFacade.PaymentCallbackCommand(
                context.projectId(), request.getOutTradeNo(), request.getActualAmountCny(),
                request.getCashierOrderNo(), request.getCashierTransactionNo(), traceId));
        log.info("[INSTORE][rpc] confirmPayment 出口 projectId={}, outTradeNo={}",
                context.projectId(), request.getOutTradeNo());
        return success(null);
    }

    @Override
    public CommonResult<Void> reportRefundResult(ReportRefundResultReqDTO request) {
        ProjectCallContextAccessor.ProjectContext context = contextAccessor.requireProjectContext();
        String traceId = traceIdOrGenerate(request.getTraceId());
        log.info("[INSTORE][rpc] reportRefundResult 入口 projectId={}, refundCaseNo={}, actualAmountCny={}, traceId={}",
                context.projectId(), request.getRefundCaseNo(), request.getActualAmountCny(), traceId);
        facade.confirmRefund(new InstoreBuyerCashierCallbackFacade.RefundCallbackCommand(
                context.projectId(), request.getRefundCaseNo(), request.getActualAmountCny(),
                request.getCashierRefundReference(), traceId));
        log.info("[INSTORE][rpc] reportRefundResult 出口 projectId={}, refundCaseNo={}",
                context.projectId(), request.getRefundCaseNo());
        return success(null);
    }

    private static String traceIdOrGenerate(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return traceId;
    }
}
```

- [x] **Step 4: 运行确认 GREEN**

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest=InstoreChannelCommandApiImplTest,InstoreBuyerCashierCallbackApiImplTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS。

- [x] **Step 5: 提交**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/interfaceapi/rpc \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/interfaceapi/rpc
git commit -m "$(cat <<'EOF'
feat(supply-chain): implement instore command and cashier callback rpc

命令侧从 ChannelContext、回调侧从 ProjectContext 取唯一身份；
traceId 缺省生成，日志只记录业务键不泄漏 userCode 与 cashierUrl。
EOF
)"
```

---

### Task 5: 架构规则 + 全量门禁回归 + 文档更新 + 最终提交

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/architecture/SupplyChainModuleArchitectureTest.java`
- Modify: `docs/供应链中台INSTORE到店购渠道接入实现总结.md`
- Modify: `docs/集成测试.md`

- [x] **Step 1: 新增架构规则**

`SupplyChainModuleArchitectureTest.java` 在 `application_never_calls_tenant_tuple_binding_lookup` 规则后新增：

```java
    /**
     * INSTORE RPC 实现层只允许依赖 Facade 与可信上下文访问器，禁止直连 DAL、集成层、
     * 场景应用层或账户核心，保证 Wire 编排不绕过应用服务边界。
     * 设计见 {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#7-测试门禁}。
     */
    static final ArchRule instore_rpc_impls_are_thin_delegates = noClasses()
            .that().resideInAPackage("..interfaceapi.rpc..")
            .and().haveSimpleNameStartingWith("Instore")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..dal..", "..integration..", "..border..", "..tradecore..");
```

并在 `@Test` 方法区新增：

```java
    @Test
    void instoreRpcImplsAreThinDelegates() {
        instore_rpc_impls_are_thin_delegates.check(importedClasses);
    }
```

- [x] **Step 2: 运行架构与契约门禁**

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -DskipTests=false \
  -Dtest=SupplyChainModuleArchitectureTest,InstorePaymentCommandApiContractTest,InstoreChannelCommandFacadeTest,InstoreChannelCommandApiImplTest,InstoreBuyerCashierCallbackApiImplTest,ProjectCallContextConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 全部 PASS（ArchitectureTest 11 条规则）。若 ArchUnit 执行显著变慢（>30 秒），先清理 macOS 同步冲突文件再重跑：

```bash
find aifuli-module-supply-chain/*/target/classes aifuli-module-supply-chain/*/target/test-classes \
  -name "* [0-9]*.class" -delete 2>/dev/null
```

- [x] **Step 3: 全量单测回归**

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -DskipTests=false test
```

Expected: BUILD SUCCESS，server（含 API 模块）全部单测通过，无回归。

- [x] **Step 4: 更新文档**

`docs/供应链中台INSTORE到店购渠道接入实现总结.md` §3.2：
- 标题改为「API 契约层（会员入口与支付/退款 Wire 已实现）」。
- 「仍缺」两条改为已实现描述：`InstoreChannelCommandApi` 五方法 + `InstoreBuyerCashierCallbackApi` 两方法 + 12 个 DTO，由 `InstoreChannelCommandApiImpl`/`InstoreBuyerCashierCallbackApiImpl` 提供 RPC 实现；命令侧身份来自 `ChannelContext`、回调侧来自新增 `ProjectContext`；Wire 建单经 `InstoreChannelCommandFacade.createPaymentOrder` 编排（稳定绑定 + 当前 CASHIER 档案版本）。契约一致性由 `InstorePaymentCommandApiContractTest` 门禁。
- 待完成列表保留：真实 SaaS 收银台 Adapter、项目 appId/appKey 统一认证、`aifuli-channel-rtmart-server`。

`docs/集成测试.md`：
- §3.4 契约与架构门禁补一行：`InstorePaymentCommandApiContractTest` 校验 12 个 Wire DTO 字段边界（禁止上下文派生字段）、Bean Validation 必填/长度、7 个 RPC 方法精确路径、`userCode/cashierUrl` toString 脱敏。
- ArchitectureTest 规则数 10→11，补 `instore_rpc_impls_are_thin_delegates` 说明。

- [x] **Step 5: 提交**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/architecture \
        docs/供应链中台INSTORE到店购渠道接入实现总结.md docs/集成测试.md
git commit -m "$(cat <<'EOF'
test(supply-chain): gate instore rpc thin delegation and update docs

新增架构规则禁止 INSTORE RPC 实现直连 DAL/集成/场景/账户核心；
实现总结与集成测试文档同步支付退款 Wire 契约完成状态。
EOF
)"
```

---

## 完成标准

- 两个 RPC 契约和本地 ApiImpl 通过全部测试上下文运行；
- `InstorePaymentCommandApiContractTest`、两个 ApiImpl 单测、扩展后的 Facade 单测、11 条架构规则、全量单测回归全部通过；
- 文档与实现同一变更内对齐，工作区无裸 TODO/TBD。

## 已知风险与回退

- `InstoreChannelCommandFacade` 构造器新增 `bindingResolver` 参数——仅单测直接 new 该类，生产走 Spring 注入，无其它调用点需改。
- 两个查询方法返回类型从 `InstoreChannelLifecycle.*View` 改为 Facade 自有 record——生产代码无其它调用点（已核实），仅 `InstoreChannelCommandFacadeTest` 两个断言需更新。
- API 模块 DTO 与 O2O 同名（`AcceptRefundReqDTO`/`ConfirmPaymentReqDTO`/`ReportRefundResultReqDTO`）——不同包不冲突，渠道服务只消费其中一个渠道的契约。
