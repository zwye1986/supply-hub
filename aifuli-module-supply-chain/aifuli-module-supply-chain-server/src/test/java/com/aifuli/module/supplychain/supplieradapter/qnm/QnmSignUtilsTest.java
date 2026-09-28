package com.aifuli.module.supplychain.supplieradapter.qnm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 奇那梦 MD5 签名与验签单元测试。
 */
class QnmSignUtilsTest {

    private static final String SECRET_KEY = "test_qnm_secret_key_123";

    @Test
    @DisplayName("按参数 ASCII 字典升序拼接并追加 key 进行 MD5 散列")
    void shouldSortParametersAlphabeticallyAndAppendKey() {
        Map<String, String> params = new HashMap<>();
        params.put("customerid", "10001");
        params.put("productid", "SKU_TELCO_100");
        params.put("billno", "BILL20260918001");
        params.put("account", "13800138000");
        params.put("number", "1");
        params.put("notifyurl", "https://api.aifuli.com/cb");
        params.put("timestamp", "1726639200");

        String signContent = QnmSignUtils.buildSignString(params, SECRET_KEY);
        assertThat(signContent).isEqualTo(
                "account=13800138000&billno=BILL20260918001&customerid=10001&notifyurl=https://api.aifuli.com/cb&number=1&productid=SKU_TELCO_100&timestamp=1726639200&key=" + SECRET_KEY
        );

        String sign = QnmSignUtils.sign(params, SECRET_KEY);
        assertThat(sign).hasSize(32);
        assertThat(sign).matches("^[a-f0-9]{32}$");

        // 验签通过
        boolean verified = QnmSignUtils.verify(params, SECRET_KEY, sign);
        assertThat(verified).isTrue();

        // 大小写不敏感验签
        boolean upperVerified = QnmSignUtils.verify(params, SECRET_KEY, sign.toUpperCase());
        assertThat(upperVerified).isTrue();
    }

    @Test
    @DisplayName("签名应忽略 null、空字符串以及 sign / sign_type 参数")
    void shouldIgnoreNullEmptyAndSignFields() {
        Map<String, String> params = new HashMap<>();
        params.put("billno", "B100");
        params.put("customerid", "C200");
        params.put("sign", "existing_sign_should_be_ignored");
        params.put("sign_type", "MD5");
        params.put("emptyVal", "");
        params.put("nullVal", null);

        String signString = QnmSignUtils.buildSignString(params, SECRET_KEY);
        assertThat(signString).isEqualTo("billno=B100&customerid=C200&key=" + SECRET_KEY);

        String sign = QnmSignUtils.sign(params, SECRET_KEY);
        assertThat(QnmSignUtils.verify(params, SECRET_KEY, sign)).isTrue();
    }

    @Test
    @DisplayName("篡改参数或密钥错误时验签必须失败")
    void shouldFailVerificationOnTamperedParamsOrWrongKey() {
        Map<String, String> params = new HashMap<>();
        params.put("billno", "B100");
        params.put("customerid", "C200");

        String sign = QnmSignUtils.sign(params, SECRET_KEY);

        // 错误密钥验签
        assertThat(QnmSignUtils.verify(params, "wrong_secret", sign)).isFalse();

        // 篡改参数验签
        params.put("billno", "B101");
        assertThat(QnmSignUtils.verify(params, SECRET_KEY, sign)).isFalse();

        // 空签名验签
        assertThat(QnmSignUtils.verify(params, SECRET_KEY, "")).isFalse();
        assertThat(QnmSignUtils.verify(params, SECRET_KEY, null)).isFalse();
    }

    @Test
    @DisplayName("空业务参数或仅含 sign 字段时必须拒绝验签，防止签名绕过")
    void shouldRejectVerificationWhenNoBusinessParamsPresent() {
        // 尝试使用空参数的 MD5 签名进行绕过
        String emptySign = QnmSignUtils.md5Hex("key=" + SECRET_KEY);
        Map<String, String> emptyParams = new HashMap<>();
        emptyParams.put("sign", emptySign);

        assertThat(QnmSignUtils.verify(emptyParams, SECRET_KEY, emptySign)).isFalse();
        assertThat(QnmSignUtils.verify(Map.of(), SECRET_KEY, emptySign)).isFalse();
        assertThat(QnmSignUtils.verify(null, SECRET_KEY, emptySign)).isFalse();
    }

    @Test
    @DisplayName("参数键包含前后空白时应自动 trim 并正确参与排序签名")
    void shouldTrimParameterKeysBeforeSorting() {
        Map<String, String> params = new HashMap<>();
        params.put(" billno ", "B100");
        params.put(" customerid", "C200");

        String signContent = QnmSignUtils.buildSignString(params, SECRET_KEY);
        assertThat(signContent).isEqualTo("billno=B100&customerid=C200&key=" + SECRET_KEY);
    }

    @Test
    @DisplayName("空密钥签名时应抛出非法参数异常")
    void shouldThrowWhenSecretKeyBlank() {
        Map<String, String> params = Map.of("billno", "B1");
        assertThatThrownBy(() -> QnmSignUtils.sign(params, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secretKey");
        assertThatThrownBy(() -> QnmSignUtils.sign(params, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secretKey");
    }
}
