package com.aifuli.module.supplychain.border.rtmart;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * 旅通入站 SHA1withRSA 签名验证器。
 *
 * <p>旅通回调商户接口时使用与出站一致的签名协议：对除 sign 外的非 null 字段按 key 字典序
 * 以 key+value 无分隔拼接规范串，对 UTF-8 字节执行 {@code SHA1withRSA} 验签，签名为标准
 * Base64。Base64 非法、密钥不符或验签失败一律返回 false，不向外抛出细节；与出站
 * {@code RtmartRequestSigner}（私钥签名）互为逆运算，验签使用旅通提供的公钥。</p>
 */
public final class RtmartSignVerifier {

    private RtmartSignVerifier() {
    }

    /**
     * 验证旅通请求签名。
     *
     * @param fields    请求中除 sign 外的所有字段（key 为旅通字段名，value 为字符串值）
     * @param sign      请求中的 sign 值（标准 Base64 编码的 SHA1withRSA 签名）
     * @param publicKey 旅通验签公钥
     * @return 签名是否匹配
     */
    public static boolean verify(Map<String, String> fields, String sign, PublicKey publicKey) {
        if (sign == null || sign.isBlank() || publicKey == null) {
            return false;
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        fields.forEach((key, value) -> {
            if (value != null) {
                sorted.put(key, value);
            }
        });
        StringBuilder canonical = new StringBuilder();
        sorted.forEach((key, value) -> canonical.append(key).append(value));
        try {
            Signature verifier = Signature.getInstance("SHA1withRSA");
            verifier.initVerify(publicKey);
            verifier.update(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(sign));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            return false;
        }
    }
}
