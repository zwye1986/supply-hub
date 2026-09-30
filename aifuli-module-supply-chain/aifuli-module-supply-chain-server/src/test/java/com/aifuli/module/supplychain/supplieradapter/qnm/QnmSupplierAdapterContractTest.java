package com.aifuli.module.supplychain.supplieradapter.qnm;

import com.aifuli.module.supplychain.border.fulfillment.domain.AdapterStepCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOperationKind;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationAssetFact;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationExecutorPort;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcome;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcomeClass;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationTicket;
import com.aifuli.module.supplychain.supplieradapter.CompositeOperationExecutor;
import com.aifuli.module.supplychain.supplieradapter.metadata.QnmGameOrderV1Metadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 奇那梦适配器与履约出站端口契约测试。
 *
 * <p>锁定领域出站端口的不可变契约模型，保证调用者与适配器之间的强类型隔离，
 * 并确保 CompositeOperationExecutor 的路由与异常语义完全符合契约规范。</p>
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#731-operationexecutorport-契约模型}。</p>
 */
class QnmSupplierAdapterContractTest {

    @Test
    @DisplayName("OperationExecutorPort 端口方法契约验证")
    void verifyPortMethodSignatures() throws NoSuchMethodException {
        Method supportsMethod = OperationExecutorPort.class.getMethod("supports", String.class);
        assertThat(supportsMethod.getReturnType()).isEqualTo(boolean.class);

        Method executeMethod = OperationExecutorPort.class.getMethod("execute", OperationTicket.class);
        assertThat(executeMethod.getReturnType()).isEqualTo(OperationOutcome.class);
    }

    @Test
    @DisplayName("OperationTicket 不可变性与防御性复制契约")
    void shouldEnsureOperationTicketImmutability() {
        Map<String, String> mutableExtra = new HashMap<>();
        mutableExtra.put("k1", "v1");

        byte[] checkpoint = new byte[]{1, 2, 3};

        OperationTicket ticket = OperationTicket.builder()
                .operationNo("OP_TICKET_01")
                .bOrderNo("BO_01")
                .billno("BILL_01")
                .adapterType(QnmGameOrderV1Metadata.TYPE)
                .operationKind(FulfillmentOperationKind.FULFILL)
                .adapterStepCode(AdapterStepCode.SEND)
                .externalSkuCode("SKU_01")
                .baseUrl("https://api.qinameng.com")
                .merchantAccount("MA_01")
                .secretKey("SECRET_01")
                .extraParameters(mutableExtra)
                .checkpointPayload(checkpoint)
                .build();

        // 外部修改 map 不影响 ticket
        mutableExtra.put("k2", "v2");
        assertThat(ticket.extraParameters()).doesNotContainKey("k2");

        // ticket 返回的 map 不可变
        assertThatThrownBy(() -> ticket.extraParameters().put("k3", "v3"))
                .isInstanceOf(UnsupportedOperationException.class);

        // 外部修改 byte[] 不影响 ticket
        checkpoint[0] = 99;
        assertThat(ticket.checkpointPayload()[0]).isEqualTo((byte) 1);
    }

    @Test
    @DisplayName("OperationOutcome 不可变性契约")
    void shouldEnsureOperationOutcomeImmutability() {
        OperationAssetFact asset = OperationAssetFact.builder()
                .sequenceNo(1)
                .assetType("CARD_CODE_SECRET")
                .cardNo("C1")
                .cardSecret("P1")
                .build();

        OperationOutcome outcome = OperationOutcome.builder()
                .outcomeClass(OperationOutcomeClass.SUCCEEDED)
                .assets(List.of(asset))
                .build();

        assertThatThrownBy(() -> outcome.assets().add(asset))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("CompositeOperationExecutor 正确路由至 QnmSupplierAdapter 并抛出不支持异常")
    void shouldRouteToQnmAdapterOrThrow() {
        QnmSupplierAdapter mockAdapter = new QnmSupplierAdapter(new QnmHttpExecutor(), new ObjectMapper());
        CompositeOperationExecutor composite = new CompositeOperationExecutor(List.of(mockAdapter));

        assertThat(composite.supports(QnmGameOrderV1Metadata.TYPE)).isTrue();
        assertThat(composite.supports("UNKNOWN_ADAPTER_TYPE")).isFalse();

        OperationTicket unknownTicket = OperationTicket.builder()
                .operationNo("OP_UNK")
                .bOrderNo("BO_UNK")
                .billno("BILL_UNK")
                .adapterType("UNKNOWN_TYPE")
                .build();

        assertThatThrownBy(() -> composite.execute(unknownTicket))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No OperationExecutorPort found for adapterType");
    }

    @Test
    @DisplayName("OperationOutcomeClass 枚举值完整性与 DDL 约束对齐")
    void shouldAlignWithDdlFactTypes() {
        List<String> ddlFactTypes = List.of(
                "ACCEPTED", "SUCCEEDED", "PARTIALLY_SUCCEEDED", "REJECTED",
                "NOT_FOUND", "RESULT_UNKNOWN", "MANUAL_REQUIRED"
        );
        for (String type : ddlFactTypes) {
            OperationOutcomeClass outcome = OperationOutcomeClass.fromName(type);
            assertThat(outcome).isNotNull();
            assertThat(outcome.name()).isEqualTo(type);
        }
    }

    @Test
    @DisplayName("OperationTicket: extraParameters 包含 null 值时不应抛出 NPE 且过滤 null")
    void shouldHandleNullValuesInExtraParametersGracefully() {
        Map<String, String> mapWithNull = new HashMap<>();
        mapWithNull.put("k1", "v1");
        mapWithNull.put("k2", null);

        OperationTicket ticket = OperationTicket.builder()
                .operationNo("OP_SAFE")
                .bOrderNo("BO_SAFE")
                .billno("BILL_SAFE")
                .adapterType(QnmGameOrderV1Metadata.TYPE)
                .extraParameters(mapWithNull)
                .build();

        assertThat(ticket.extraParameters()).containsEntry("k1", "v1");
        assertThat(ticket.extraParameters()).doesNotContainKey("k2");
    }

    @Test
    @DisplayName("OperationOutcome: assets 包含 null 元素时不应抛出 NPE 且过滤 null")
    void shouldFilterNullAssetsInOperationOutcome() {
        List<OperationAssetFact> assetsWithNull = Arrays.asList(
                OperationAssetFact.builder().cardNo("C1").build(),
                null
        );

        OperationOutcome outcome = OperationOutcome.builder()
                .outcomeClass(OperationOutcomeClass.SUCCEEDED)
                .assets(assetsWithNull)
                .build();

        assertThat(outcome.assets()).hasSize(1);
        assertThat(outcome.assets().get(0).cardNo()).isEqualTo("C1");
    }

    @Test
    @DisplayName("OperationAssetFact: 无卡密且有卡号时自动分类为 VOUCHER_CODE")
    void shouldClassifyVoucherCodeWhenSecretAbsent() {
        OperationAssetFact voucher = OperationAssetFact.builder()
                .cardNo("COUPON_123")
                .build();
        assertThat(voucher.assetType()).isEqualTo("VOUCHER_CODE");

        OperationAssetFact cardWithSecret = OperationAssetFact.builder()
                .cardNo("CARD_123")
                .cardSecret("SECRET_123")
                .build();
        assertThat(cardWithSecret.assetType()).isEqualTo("CARD_CODE_SECRET");
    }

    @Test
    @DisplayName("OperationAssetFact: 空白字符 cardNo 与 cardSecret 应规格化为 null，externalAssetId 应 trim 并转 null")
    void shouldNormalizeBlankCardFieldsToNull() {
        OperationAssetFact fact = OperationAssetFact.builder()
                .cardNo("   ")
                .cardSecret(" \t ")
                .externalAssetId("  ASSET_01  ")
                .build();
        assertThat(fact.cardNo()).isNull();
        assertThat(fact.cardSecret()).isNull();
        assertThat(fact.externalAssetId()).isEqualTo("ASSET_01");
        assertThat(fact.assetType()).isEqualTo("CARD_CODE_SECRET");

        OperationAssetFact blankAssetId = OperationAssetFact.builder()
                .externalAssetId("   ")
                .build();
        assertThat(blankAssetId.externalAssetId()).isNull();
    }

    @Test
    @DisplayName("OperationTicket: operationNo, billno, adapterType 传空白字符时抛出 IllegalArgumentException")
    void shouldRejectBlankIdentifiersInOperationTicket() {
        assertThatThrownBy(() -> OperationTicket.builder()
                .operationNo("   ")
                .billno("B1")
                .adapterType(QnmGameOrderV1Metadata.TYPE)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operationNo must not be blank");

        assertThatThrownBy(() -> OperationTicket.builder()
                .operationNo("OP1")
                .billno("   ")
                .adapterType(QnmGameOrderV1Metadata.TYPE)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("billno must not be blank");

        assertThatThrownBy(() -> OperationTicket.builder()
                .operationNo("OP1")
                .billno("B1")
                .adapterType("   ")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("adapterType must not be blank");
    }

    @Test
    @DisplayName("RawObservationFact: observedAt 为空时自动兜底为当前系统时间")
    void shouldDefaultObservedAtWhenNull() {
        com.aifuli.module.supplychain.border.fulfillment.domain.RawObservationFact fact =
                com.aifuli.module.supplychain.border.fulfillment.domain.RawObservationFact.builder()
                        .sourceType("TEST")
                        .build();
        assertThat(fact.observedAt()).isNotNull();
    }

    @Test
    @DisplayName("CompositeOperationExecutor: delegates 为 null 时安全初始化为空列表，不抛 NPE")
    void shouldHandleNullDelegatesInCompositeExecutor() {
        CompositeOperationExecutor composite = new CompositeOperationExecutor(null);
        assertThat(composite.supports(QnmGameOrderV1Metadata.TYPE)).isFalse();

        OperationTicket ticket = OperationTicket.builder()
                .operationNo("OP_SAFE")
                .billno("BILL_SAFE")
                .adapterType(QnmGameOrderV1Metadata.TYPE)
                .build();
        assertThatThrownBy(() -> composite.execute(ticket))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No OperationExecutorPort found");
    }
}
