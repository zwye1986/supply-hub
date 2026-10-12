package com.aifuli.module.supplychain.border.fulfillment.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 格式 2 指纹策略固定向量单测：字段顺序稳定、长度前缀消除拼接歧义、可空字段哨兵确定，
 * 并以硬编码十六进制锁死「无用户」字段集，防止回归混入购买人身份、支付时间、版本或 token。
 *
 * <p>设计见 {@code docs/superpowers/specs/2026-10-02-standard-acceptance-project-key-design.md#2-首次身份与重放优先}。</p>
 */
class StandardAcceptanceFingerprintPolicyTest {

    private static final String BUYER_ID = "1";
    private static final String PROJECT_CODE = "PRJ_FP";
    private static final String BUYER_ORDER_NO = "C-FP-1";
    private static final String BUYER_SKU_CODE = "SKU-FP";
    private static final String SCHEMA_CODE = "DIRECT_RECHARGE_TARGET";
    private static final String DIGEST_HEX = "aabbcc";

    /**
     * 固定向量：按「int32 大端字段名长度 + UTF-8 字段名 + int32 大端字段值长度 + UTF-8 字段值」
     * 独立重建的字节，十六进制锁死字段顺序与取值边界。
     */
    private static final String FIXED_VECTOR_HEX =
            "0000000d666f726d617456657273696f6e000000013200000007627579657249640000000131"
                    + "0000000b70726f6a656374436f64650000000650524a5f4650"
                    + "0000000c62757965724f726465724e6f00000006432d46502d31"
                    + "0000000c6275796572536b75436f646500000006534b552d4650"
                    + "0000001366726f7a656e506c6174666f726d536b7549640000000137"
                    + "000000087175616e746974790000000131"
                    + "00000010746172676574536368656d61436f6465000000164449524543545f52454348415247455f544152474554"
                    + "00000013746172676574536368656d6156657273696f6e0000000131"
                    + "0000000f74617267657444696765737448657800000006616162626363";

    private static final String NULL_SKU_VECTOR_HEX =
            "0000000d666f726d617456657273696f6e000000013200000007627579657249640000000131"
                    + "0000000b70726f6a656374436f64650000000650524a5f4650"
                    + "0000000c62757965724f726465724e6f00000006432d46502d31"
                    + "0000000c6275796572536b75436f646500000006534b552d4650"
                    + "0000001366726f7a656e506c6174666f726d536b754964ffffffff"
                    + "000000087175616e746974790000000131"
                    + "00000010746172676574536368656d61436f6465000000164449524543545f52454348415247455f544152474554"
                    + "00000013746172676574536368656d6156657273696f6e0000000131"
                    + "0000000f74617267657444696765737448657800000006616162626363";

    private static final String BUYER_ORDER_DIGEST_VECTOR_HEX =
            "0000000d666f726d617456657273696f6e0000000132"
                    + "0000000b70726f6a656374436f64650000000650524a5f4650"
                    + "0000000c62757965724f726465724e6f00000006432d46502d31";

    private static StandardAcceptanceFingerprintPolicy.RequestIdentity identity(Long frozenPlatformSkuId) {
        return new StandardAcceptanceFingerprintPolicy.RequestIdentity(
                Long.valueOf(BUYER_ID), PROJECT_CODE, BUYER_ORDER_NO, BUYER_SKU_CODE,
                frozenPlatformSkuId, 1L, SCHEMA_CODE, 1, DIGEST_HEX);
    }

    @Test
    void fixedVectorLocksFieldOrderAndEncoding() {
        assertThat(hex(StandardAcceptanceFingerprintPolicy.canonicalRequest(identity(7L))))
                .isEqualTo(FIXED_VECTOR_HEX);
        // 同一输入重复编码字节完全一致（字段顺序与编码确定性）。
        assertThat(StandardAcceptanceFingerprintPolicy.canonicalRequest(identity(7L)))
                .isEqualTo(StandardAcceptanceFingerprintPolicy.canonicalRequest(identity(7L)));
    }

    @Test
    void nullFrozenSkuIdentityUsesMinusOneSentinelDeterministically() {
        assertThat(hex(StandardAcceptanceFingerprintPolicy.canonicalRequest(identity(null))))
                .isEqualTo(NULL_SKU_VECTOR_HEX);
        assertThat(StandardAcceptanceFingerprintPolicy.canonicalRequest(identity(null)))
                .isEqualTo(StandardAcceptanceFingerprintPolicy.canonicalRequest(identity(null)))
                .isNotEqualTo(StandardAcceptanceFingerprintPolicy.canonicalRequest(identity(7L)));
    }

    @Test
    void lengthPrefixDisambiguatesConcatenationAcrossFields() {
        // 「a|b + c」与「a + b|c」：旧实现的分隔符拼接会得到相同串，长度前缀编码必须区分。
        StandardAcceptanceFingerprintPolicy.RequestIdentity first = identity(7L);
        StandardAcceptanceFingerprintPolicy.RequestIdentity second = new StandardAcceptanceFingerprintPolicy.RequestIdentity(
                Long.valueOf(BUYER_ID), PROJECT_CODE, "a", "b|c", 7L, 1L, SCHEMA_CODE, 1, DIGEST_HEX);
        StandardAcceptanceFingerprintPolicy.RequestIdentity third = new StandardAcceptanceFingerprintPolicy.RequestIdentity(
                Long.valueOf(BUYER_ID), PROJECT_CODE, "a|b", "c", 7L, 1L, SCHEMA_CODE, 1, DIGEST_HEX);

        byte[] secondBytes = StandardAcceptanceFingerprintPolicy.canonicalRequest(second);
        byte[] thirdBytes = StandardAcceptanceFingerprintPolicy.canonicalRequest(third);
        assertThat(secondBytes).isNotEqualTo(thirdBytes);
        assertThat(secondBytes).isNotEqualTo(
                StandardAcceptanceFingerprintPolicy.canonicalRequest(first));
    }

    @Test
    void requestIdentityCarriesNoUserPaymentVersionOrTokenComponent() {
        // 「无用户」承诺落在字段闭包上：record 组件不得出现购买人身份、支付时间、调用方版本或报价 token。
        assertThat(java.util.Arrays.stream(
                        StandardAcceptanceFingerprintPolicy.RequestIdentity.class.getRecordComponents())
                .map(component -> component.getName()))
                .doesNotContain("buyerUserId", "paidAt", "listingVersion", "pricingQuoteToken")
                .containsExactly("buyerId", "projectCode", "buyerOrderNo", "buyerSkuCode",
                        "frozenPlatformSkuId", "quantity", "targetSchemaCode", "targetSchemaVersion",
                        "targetDigestHex");
    }

    @Test
    void buyerOrderDigestInputContainsProjectAndOrderOnly() {
        assertThat(hex(StandardAcceptanceFingerprintPolicy.canonicalBuyerOrderDigestInput(
                PROJECT_CODE, BUYER_ORDER_NO))).isEqualTo(BUYER_ORDER_DIGEST_VECTOR_HEX);
        assertThat(StandardAcceptanceFingerprintPolicy.canonicalBuyerOrderDigestInput(
                PROJECT_CODE, BUYER_ORDER_NO))
                .isEqualTo(StandardAcceptanceFingerprintPolicy.canonicalBuyerOrderDigestInput(
                        PROJECT_CODE, BUYER_ORDER_NO));
        assertThatThrownBy(() -> StandardAcceptanceFingerprintPolicy.canonicalBuyerOrderDigestInput(
                " ", BUYER_ORDER_NO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StandardAcceptanceFingerprintPolicy.canonicalBuyerOrderDigestInput(
                PROJECT_CODE, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void platformSkuFormatHasIndependentVectorAndPreservesRawCode() {
        // 独立按协议字段名/长度编码的向量，不从生产算法导出期望值。
        String expected = "0000000d666f726d617456657273696f6e000000013300000007627579657249640000000131"
                + "0000000b70726f6a656374436f64650000000650524a5f46500000000c62757965724f726465724e6f00000006432d46502d31"
                + "0000000f70726f64756374436f6465547970650000000c504c4154464f524d5f534b55"
                + "0000000f706c6174666f726d536b75436f646500000006534b552d4650"
                + "0000001366726f7a656e506c6174666f726d536b7549640000000137000000087175616e746974790000000131"
                + "00000010746172676574536368656d61436f6465000000164449524543545f52454348415247455f544152474554"
                + "00000013746172676574536368656d6156657273696f6e0000000131"
                + "0000000f74617267657444696765737448657800000006616162626363";
        byte[] actual = StandardAcceptanceFingerprintPolicy.canonicalPlatformSkuRequest(identity(7L));
        assertThat(hex(actual)).isEqualTo(expected).isNotEqualTo(FIXED_VECTOR_HEX);
        var changed = new StandardAcceptanceFingerprintPolicy.RequestIdentity(1L, PROJECT_CODE, BUYER_ORDER_NO,
                "sku-fp", 7L, 1, SCHEMA_CODE, 1, DIGEST_HEX);
        assertThat(StandardAcceptanceFingerprintPolicy.canonicalPlatformSkuRequest(changed)).isNotEqualTo(actual);
        var targetChanged = new StandardAcceptanceFingerprintPolicy.RequestIdentity(1L, PROJECT_CODE, BUYER_ORDER_NO,
                BUYER_SKU_CODE, 7L, 1, SCHEMA_CODE, 1, "aabbcd");
        assertThat(StandardAcceptanceFingerprintPolicy.canonicalPlatformSkuRequest(targetChanged)).isNotEqualTo(actual);
    }

    private static String hex(byte[] value) {
        return java.util.HexFormat.of().formatHex(value);
    }
}
