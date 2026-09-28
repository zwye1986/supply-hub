package com.aifuli.module.supplychain.supplieradapter.qnm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 奇那梦日志脱敏与传输防风暴工具测试。
 */
class QnmMaskingUtilsTest {

    @Test
    @DisplayName("充值账号脱敏：7位及以下全掩码，超过7位保留前3后4")
    void shouldMaskAccountCorrectly() {
        assertThat(QnmMaskingUtils.maskAccount(null)).isEmpty();
        assertThat(QnmMaskingUtils.maskAccount("")).isEmpty();
        assertThat(QnmMaskingUtils.maskAccount("   ")).isEmpty();
        assertThat(QnmMaskingUtils.maskAccount("123456")).isEqualTo("****");
        assertThat(QnmMaskingUtils.maskAccount("1234567")).isEqualTo("****");
        assertThat(QnmMaskingUtils.maskAccount("13800138000")).isEqualTo("138****8000");
        assertThat(QnmMaskingUtils.maskAccount(" test@user.com ")).isEqualTo("tes****.com");
    }

    @Test
    @DisplayName("卡密脱敏：只要非空一律返回掩码")
    void shouldMaskSecretCorrectly() {
        assertThat(QnmMaskingUtils.maskSecret(null)).isEmpty();
        assertThat(QnmMaskingUtils.maskSecret("")).isEmpty();
        assertThat(QnmMaskingUtils.maskSecret("   ")).isEmpty();
        assertThat(QnmMaskingUtils.maskSecret("PWD123456")).isEqualTo("****");
    }

    @Test
    @DisplayName("卡号脱敏：8位及以下全掩码，超过8位保留前4后4")
    void shouldMaskCardNoCorrectly() {
        assertThat(QnmMaskingUtils.maskCardNo(null)).isEmpty();
        assertThat(QnmMaskingUtils.maskCardNo("")).isEmpty();
        assertThat(QnmMaskingUtils.maskCardNo("   ")).isEmpty();
        assertThat(QnmMaskingUtils.maskCardNo("12345678")).isEqualTo("****");
        assertThat(QnmMaskingUtils.maskCardNo("6222021234567890")).isEqualTo("6222****7890");
    }

    @Test
    @DisplayName("参数快照脱敏：脱敏账号、签名、卡密、密钥，并返回不可变Map")
    void shouldMaskParamsCorrectly() {
        Map<String, String> params = new HashMap<>();
        params.put("customerid", "M10001");
        params.put("account", "13800138000");
        params.put("sign", "12345678abcdef12345678abcdef1234");
        params.put("cards", "[{\"cardNo\":\"123\",\"cardPwd\":\"pwd\"}]");
        params.put("secretKey", "super_secret_key");
        params.put("normalField", "normalValue");
        params.put("nullVal", null);

        Map<String, String> masked = QnmMaskingUtils.maskParams(params);

        assertThat(masked.get("customerid")).isEqualTo("M10001");
        assertThat(masked.get("account")).isEqualTo("138****8000");
        assertThat(masked.get("sign")).isEqualTo("1234****1234");
        assertThat(masked.get("cards")).isEqualTo("****");
        assertThat(masked.get("secretKey")).isEqualTo("****");
        assertThat(masked.get("normalField")).isEqualTo("normalValue");
        assertThat(masked).doesNotContainKey("nullVal");

        // 不可变断言
        assertThatThrownBy(() -> masked.put("k", "v"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("空参数或null参数返回不可变空Map")
    void shouldHandleEmptyOrNullParams() {
        assertThat(QnmMaskingUtils.maskParams(null)).isEmpty();
        assertThat(QnmMaskingUtils.maskParams(Map.of())).isEmpty();
    }

    @Test
    @DisplayName("参数快照脱敏：覆盖各类变体字段名（card_pwd, card_secret, token, appSecret, cardNumber, mobile等）")
    void shouldMaskAllSensitiveVariants() {
        Map<String, String> params = new HashMap<>();
        params.put("card_pwd", "SECRET_PWD_1");
        params.put("card_secret", "SECRET_KEY_2");
        params.put("token", "TOKEN_ABC_123");
        params.put("appSecret", "APP_SECRET_999");
        params.put("cardNumber", "6222021234567890");
        params.put("card_number", "6222021234567891");
        params.put("phone", "13912345678");
        params.put("mobile", "13787654321");
        params.put("rechargeAccount", "13600001111");

        Map<String, String> masked = QnmMaskingUtils.maskParams(params);

        assertThat(masked.get("card_pwd")).isEqualTo("****");
        assertThat(masked.get("card_secret")).isEqualTo("****");
        assertThat(masked.get("token")).isEqualTo("****");
        assertThat(masked.get("appSecret")).isEqualTo("****");
        assertThat(masked.get("cardNumber")).isEqualTo("6222****7890");
        assertThat(masked.get("card_number")).isEqualTo("6222****7891");
        assertThat(masked.get("phone")).isEqualTo("139****5678");
        assertThat(masked.get("mobile")).isEqualTo("137****4321");
        assertThat(masked.get("rechargeAccount")).isEqualTo("136****1111");
    }

    @Test
    @DisplayName("响应体脱敏：屏蔽 cards/卡密/账号，禁止明文进入日志")
    void shouldMaskResponseBodySecretsAndKeepBusinessFields() {
        String body = "{\"ResCode\":\"ok\",\"account\":\"13800138000\","
                + "\"cards\":[{\"cardNo\":\"6222021234567890\",\"cardPwd\":\"PWD123456\"}]}";
        String masked = QnmMaskingUtils.maskResponseBody(body);

        assertThat(masked).contains("\"ResCode\":\"ok\"");
        assertThat(masked).doesNotContain("PWD123456");
        assertThat(masked).doesNotContain("6222021234567890");
        assertThat(masked).doesNotContain("13800138000");
        assertThat(masked).contains("****");
    }

    @Test
    @DisplayName("响应体脱敏：空值返回空串，超长截断防风暴")
    void shouldHandleBlankAndTruncateLongResponseBody() {
        assertThat(QnmMaskingUtils.maskResponseBody(null)).isEmpty();
        assertThat(QnmMaskingUtils.maskResponseBody("  ")).isEmpty();

        String longBody = "x".repeat(600);
        String masked = QnmMaskingUtils.maskResponseBody(longBody);
        assertThat(masked).startsWith("xxxx");
        assertThat(masked).contains("truncated,len=600");
        assertThat(masked.length()).isLessThan(longBody.length());
    }
}
