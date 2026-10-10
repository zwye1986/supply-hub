package com.aifuli.module.supplychain.application.facade;

import com.aifuli.module.supplychain.api.fulfillment.dto.AcceptFulfillmentReqDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentAcceptanceReceiptRespDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentReceiptStatus;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentTargetDTO;
import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentAcceptanceLifecycle;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentAcceptanceLifecycle.AcceptanceSnapshot;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentAcceptanceLifecycle.ClaimResult;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentAcceptanceLifecycle.PreparedAcceptance;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentAcceptanceLifecycle.TargetSchemaSnapshot;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentTargetSchemaRegistry;
import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyAcceptanceCoordinator;
import com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceReceiptStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceRejectionCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTargetFields;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.infrastructure.security.HmacFingerprintPort;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerProjectAccessResolver;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「标准受理的项目永久键与身份清理」行为测试（GREEN：断言新语义已由生产代码实现）。
 *
 * <p>验证设计 docs/superpowers/specs/2026-10-02-standard-acceptance-project-key-design.md：
 * <ul>
 *   <li>§1 双入口与共享命令：标准内部受理命令删除 buyerUserId/listingVersion/pricingQuoteToken/paidAt，
 *       受理不再要求购买人身份、客户端商品版本、报价凭证串与支付时间。本测试类不引用这四个待删字段的
 *       getter/setter，字段删除后无需机械改动即可继续编译；</li>
 *   <li>§3 回执竞争、重试和真实返回：首次与重放的 requestId 都必须返回实际回执号（receiptNo），
 *       不能每次生成新的 REQ 前缀号冒充稳定回执号；重放返回订单表当前真实 orderStatus，
 *       未建单显式 null，不得恒写 PROCESSING。</li>
 * </ul>
 *
 * <p>过渡期说明：用例二、三、四的请求经 JSON 反序列化构造——文本中残留的四个待删字段键
 * 成为未知属性被 {@link #TRANSITIONAL_MAPPER} 忽略，证明契约删除后旧报文不致反序列化失败。
 * {@code LENIENT} 宽棒策略保留自红灯阶段，便于聚焦断言失败信息。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BuyerFulfillmentCommandFacadeTest {

    /** 明显假号 fixture（大陆手机号格式）；按契约不写入日志。 */
    private static final String FAKE_MOBILE = "13800138000";
    private static final String SOURCE_SYSTEM = "AIFULI_MALL";
    private static final String EXTERNAL_TENANT_ID = "tenant-wusheng";
    private static final String PROJECT_CODE = "PRJ_KEY_UNIT";
    private static final String BUYER_ORDER_NO = "C20261002000001";
    private static final String BUYER_SKU_CODE = "WS-TELCO-100";
    private static final String TARGET_SCHEMA_CODE = "DIRECT_RECHARGE_TARGET";
    private static final long RECEIPT_ID = 101L;
    /** 回执号 fixture：新语义要求首次与重放的 requestId 都等于该实际回执号。 */
    private static final String RECEIPT_NO = "RCP_UNIT_STABLE_0001";
    private static final long RECEIPT_VERSION = 3L;
    /** 当前实现每次受理生成的 REQ 前缀号 fixture，用于凸显新语义下的差异。 */
    private static final String GENERATED_REQ_FIRST = "REQ_GENERATED_FIRST";
    private static final String GENERATED_REQ_SECOND = "REQ_GENERATED_SECOND";

    /**
     * 共享 Jackson 映射器：忽略未知属性，保证待删字段移除后过渡期 JSON fixture 仍可反序列化。
     */
    private static final ObjectMapper TRANSITIONAL_MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Mock
    private SupplyChainDatabaseClock databaseClock;
    @Mock
    private BuyerResolver buyerResolver;
    @Mock
    private BuyerProjectAccessResolver projectAccessResolver;
    @Mock
    private com.aifuli.module.supplychain.border.fulfillment.application.FrozenAcceptanceIdentityReader frozenIdentityReader;
    @Mock
    private FulfillmentTargetSchemaRegistry targetSchemaRegistry;
    @Mock
    private EnvelopeCryptoPort envelopeCrypto;
    @Mock
    private HmacFingerprintPort hmacFingerprint;
    @Mock
    private FulfillmentAcceptanceLifecycle lifecycle;
    @Mock
    private RechargeFrequencyAcceptanceCoordinator acceptanceCoordinator;
    @Mock
    private BusinessNoGenerator businessNoGenerator;

    private BuyerFulfillmentCommandFacade facade;

    @BeforeEach
    void setUp() {
        facade = new BuyerFulfillmentCommandFacade(databaseClock, buyerResolver, projectAccessResolver,
                frozenIdentityReader,
                targetSchemaRegistry, envelopeCrypto, hmacFingerprint, lifecycle, acceptanceCoordinator,
                businessNoGenerator, TRANSITIONAL_MAPPER);
    }

    /**
     * 用例一（设计 §1）：受理请求只携带 sourceSystem/externalTenantId/projectCode/buyerOrderNo/
     * buyerSkuCode/quantity/target，四个待删字段全部缺失。
     *
     * <p>断言：不因缺失购买人身份、客户端商品版本、报价凭证串与支付时间抛出校验异常，
     * 且流程进入 claim 并沿重放路径返回回执。</p>
     */
    @Test
    void acceptFulfillmentNoLongerRequiresRemovedIdentityFields() {
        stubTrustedIdentityResolution();
        when(lifecycle.claim(any(PreparedAcceptance.class)))
                .thenReturn(new ClaimResult(RECEIPT_ID, false, true));
        when(lifecycle.replay(RECEIPT_ID)).thenReturn(snapshot(true));

        AcceptFulfillmentReqDTO request = new AcceptFulfillmentReqDTO();
        request.setSourceSystem(SOURCE_SYSTEM);
        request.setExternalTenantId(EXTERNAL_TENANT_ID);
        request.setProjectCode(PROJECT_CODE);
        request.setBuyerOrderNo(BUYER_ORDER_NO);
        request.setBuyerSkuCode(BUYER_SKU_CODE);
        request.setQuantity(1L);
        request.setTarget(mobileTarget(FAKE_MOBILE));

        FulfillmentAcceptanceReceiptRespDTO response = facade.acceptFulfillment(request);

        verify(lifecycle).claim(any(PreparedAcceptance.class));
        assertThat(response.getIdempotentReplay()).isTrue();
        assertThat(response.getBusinessNo()).isEqualTo(BUYER_ORDER_NO);
    }

    /**
     * 用例二（设计 §3）：同一请求首次受理成功后再次调用，重放必须返回稳定 requestId。
     *
     * <p>断言：重放响应的 requestId 与首次响应完全一致（都为实际回执号）。</p>
     */
    @Test
    void replayReturnsStableRequestIdAcrossRepeatedCalls() throws Exception {
        stubTrustedIdentityResolution();
        when(businessNoGenerator.next(anyString()))
                .thenReturn(GENERATED_REQ_FIRST, GENERATED_REQ_SECOND);
        when(lifecycle.claim(any(PreparedAcceptance.class)))
                .thenReturn(new ClaimResult(RECEIPT_ID, true, false))
                .thenReturn(new ClaimResult(RECEIPT_ID, false, true));
        when(acceptanceCoordinator.accept(eq(RECEIPT_ID), any(PreparedAcceptance.class)))
                .thenReturn(snapshot(false));
        when(lifecycle.replay(RECEIPT_ID)).thenReturn(snapshot(true));

        AcceptFulfillmentReqDTO request = transitionalValidatedRequest();
        FulfillmentAcceptanceReceiptRespDTO first = facade.acceptFulfillment(request);
        FulfillmentAcceptanceReceiptRespDTO replayed = facade.acceptFulfillment(request);

        assertThat(first.getReceiptStatus()).isEqualTo(FulfillmentReceiptStatus.ACCEPTED);
        assertThat(replayed.getIdempotentReplay()).isTrue();
        assertThat(replayed.getRequestId()).isEqualTo(first.getRequestId());
    }

    /**
     * 用例三（设计 §3）：首次受理返回的 requestId 就是回执身份（实际回执号），
     * 不是每次新生成的 REQ 业务号。
     *
     * <p>断言：首次与重放响应的 requestId 都等于回执号。</p>
     */
    @Test
    void firstAcceptanceRequestIdIsTheActualReceiptNo() throws Exception {
        stubTrustedIdentityResolution();
        when(businessNoGenerator.next(anyString()))
                .thenReturn(GENERATED_REQ_FIRST, GENERATED_REQ_SECOND);
        when(lifecycle.claim(any(PreparedAcceptance.class)))
                .thenReturn(new ClaimResult(RECEIPT_ID, true, false))
                .thenReturn(new ClaimResult(RECEIPT_ID, false, true));
        when(acceptanceCoordinator.accept(eq(RECEIPT_ID), any(PreparedAcceptance.class)))
                .thenReturn(snapshot(false));
        when(lifecycle.replay(RECEIPT_ID)).thenReturn(snapshot(true));

        AcceptFulfillmentReqDTO request = transitionalValidatedRequest();
        FulfillmentAcceptanceReceiptRespDTO first = facade.acceptFulfillment(request);
        FulfillmentAcceptanceReceiptRespDTO replayed = facade.acceptFulfillment(request);

        assertThat(first.getRequestId()).isEqualTo(RECEIPT_NO);
        assertThat(replayed.getRequestId()).isEqualTo(RECEIPT_NO);
    }

    /**
     * 用例四（设计 §3）：重放返回订单表当前真实 orderStatus，快照为 SUCCEEDED 时
     * 响应不得恒写 PROCESSING（上一步推迟的断言在本步补齐）。
     */
    @Test
    void replayReturnsActualOrderStatusFromSnapshotInsteadOfAlwaysProcessing() throws Exception {
        stubTrustedIdentityResolution();
        when(lifecycle.claim(any(PreparedAcceptance.class)))
                .thenReturn(new ClaimResult(RECEIPT_ID, true, false));
        when(acceptanceCoordinator.accept(eq(RECEIPT_ID), any(PreparedAcceptance.class)))
                .thenReturn(snapshot(false, SupplyChainOrderStatus.SUCCEEDED.getCode()));
        when(lifecycle.replay(RECEIPT_ID))
                .thenReturn(snapshot(true, SupplyChainOrderStatus.SUCCEEDED.getCode()));

        FulfillmentAcceptanceReceiptRespDTO first = facade.acceptFulfillment(transitionalValidatedRequest());
        FulfillmentAcceptanceReceiptRespDTO replayed = facade.acceptFulfillment(transitionalValidatedRequest());

        assertThat(first.getOrderStatus()).isEqualTo(SupplyChainOrderStatus.SUCCEEDED);
        assertThat(replayed.getOrderStatus()).isEqualTo(SupplyChainOrderStatus.SUCCEEDED)
                .isNotEqualTo(SupplyChainOrderStatus.PROCESSING);
    }

    /**
     * 用例五（设计 §3）：未建单（orderNo 为空）响应 orderStatus 显式 null，不再恒写 PROCESSING。
     */
    @Test
    void responseWithoutOrderLeavesOrderStatusExplicitlyNull() throws Exception {
        stubTrustedIdentityResolution();
        when(lifecycle.claim(any(PreparedAcceptance.class)))
                .thenReturn(new ClaimResult(RECEIPT_ID, false, true));
        when(lifecycle.replay(RECEIPT_ID)).thenReturn(rejectedSnapshotWithoutOrder());

        FulfillmentAcceptanceReceiptRespDTO response = facade.acceptFulfillment(transitionalValidatedRequest());

        assertThat(response.getOrderNo()).isNull();
        assertThat(response.getOrderStatus()).isNull();
    }

    /**
     * 桩掉受理前置的身份与 Schema 解析协作，使流程可信地抵达 lifecycle.claim。
     */
    private void stubTrustedIdentityResolution() {
        when(databaseClock.now()).thenReturn(LocalDateTime.of(2026, 10, 2, 12, 0));
        when(buyerResolver.resolve(eq(SOURCE_SYSTEM), eq(EXTERNAL_TENANT_ID), any(LocalDateTime.class)))
                .thenReturn(new BuyerResolver.BuyerSnapshot(1L, "BYR_001", "吾盛", "吾盛", 1));
        when(projectAccessResolver.requireActiveByCode(PROJECT_CODE, 1L))
                .thenReturn(new BuyerProjectAccessResolver.ProjectSnapshot(
                        11L, PROJECT_CODE, "项目键单元测试项目", 1L));
        when(lifecycle.resolveTargetSchema(1L, BUYER_SKU_CODE))
                .thenReturn(new TargetSchemaSnapshot(4L, TARGET_SCHEMA_CODE, 1, true));
        when(hmacFingerprint.fingerprint(anyString(), any(byte[].class))).thenReturn(new byte[32]);
        when(envelopeCrypto.encrypt(any(byte[].class), any(byte[].class)))
                .thenReturn(new EnvelopeCryptoPort.EncryptedPayload(new byte[32], "unit-test-v1"));
    }

    /**
     * 已受理回执快照 fixture：回执号、状态与 CAS 版本固定，acceptedAt 为空避免时钟换算桩；
     * orderStatus 默认与受理完成时订单初始状态 PROCESSING 一致。
     */
    private static AcceptanceSnapshot snapshot(boolean idempotentReplay) {
        return snapshot(idempotentReplay, SupplyChainOrderStatus.PROCESSING.getCode());
    }

    private static AcceptanceSnapshot snapshot(boolean idempotentReplay, short orderStatus) {
        return new AcceptanceSnapshot(RECEIPT_NO, BUYER_ORDER_NO,
                AcceptanceReceiptStatus.ACCEPTED.getCode(), RECEIPT_VERSION,
                "SC_UNIT_ORDER_0001", "FR_UNIT_REQUEST_0001", null, "QINAMENG", "奇那梦",
                null, idempotentReplay, orderStatus);
    }

    /** 未建单的最终拒绝快照 fixture：orderNo/orderStatus/fulfillmentRequestNo 显式为空。 */
    private static AcceptanceSnapshot rejectedSnapshotWithoutOrder() {
        return new AcceptanceSnapshot(RECEIPT_NO, BUYER_ORDER_NO,
                AcceptanceReceiptStatus.REJECTED_FINAL.getCode(), RECEIPT_VERSION,
                null, null, AcceptanceRejectionCode.QUANTITY_NOT_SUPPORTED.name(), null, null,
                null, true, null);
    }

    /**
     * 过渡期请求 fixture：经 JSON 填充当前实现强制要求、设计将删除的字段。
     * 字段删除后这些键成为未知属性被忽略，本方法无需改动。
     */
    private static AcceptFulfillmentReqDTO transitionalValidatedRequest() throws JsonProcessingException {
        String json = """
                {
                  "sourceSystem": "%s",
                  "externalTenantId": "%s",
                  "projectCode": "%s",
                  "buyerOrderNo": "%s",
                  "buyerUserId": "%s",
                  "buyerSkuCode": "%s",
                  "listingVersion": 1,
                  "pricingQuoteToken": "unit-test-quote-token",
                  "quantity": 1,
                  "paidAt": "2026-10-02T10:30:00+08:00",
                  "target": {
                    "values": {
                      "accountType": "MOBILE",
                      "accountValue": "%s"
                    }
                  }
                }
                """.formatted(SOURCE_SYSTEM, EXTERNAL_TENANT_ID, PROJECT_CODE, BUYER_ORDER_NO,
                FAKE_MOBILE, BUYER_SKU_CODE, FAKE_MOBILE);
        return TRANSITIONAL_MAPPER.readValue(json, AcceptFulfillmentReqDTO.class);
    }

    private static FulfillmentTargetDTO mobileTarget(String accountValue) {
        FulfillmentTargetDTO target = new FulfillmentTargetDTO();
        target.setValues(Map.of(
                FulfillmentTargetFields.ACCOUNT_TYPE, FulfillmentTargetFields.ACCOUNT_TYPE_MOBILE,
                FulfillmentTargetFields.ACCOUNT_VALUE, accountValue));
        return target;
    }
}
