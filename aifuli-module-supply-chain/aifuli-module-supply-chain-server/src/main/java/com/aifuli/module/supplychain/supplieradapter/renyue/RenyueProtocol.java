package com.aifuli.module.supplychain.supplieradapter.renyue;

import com.aifuli.module.supplychain.border.fulfillment.domain.AdapterStepCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOperationKind;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationTicket;
import com.aifuli.module.supplychain.border.fulfillment.domain.ResolvedCredentialMaterial;
import com.aifuli.module.supplychain.tradecore.account.domain.Money;

import javax.crypto.Cipher;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 仁跃直充请求及密码基础，不解释状态、不产出认证事实、不参与HTTP或资金处理。
 * 材料由调用方关闭；只借用临时副本，密码对象不跨调用复用，不输出凭据或回执明文。
 * 设计：docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#511-独立向量核验与密码基础切片2026-10-18。
 */
public final class RenyueProtocol {
    public static final String MERCHANT_KEY = "merchantKey";
    public static final String PRIVATE_KEY = "privateKey";
    private static final Set<String> EXCLUDED_REQUEST_FIELDS = Set.of("SignSecret", "ExpandParams", "MobileNo");
    private static final byte[] MERCHANT_KEY_SUFFIX = "&MerchantKey=".getBytes(StandardCharsets.UTF_8);
    private static final int MAX_ENCODED_LENGTH = 1024 * 1024;
    private static final String PEM_BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String PEM_END = "-----END PRIVATE KEY-----";

    private RenyueProtocol() { }

    /**
     * 只投影原单冻结材料到直充创建/查单字段，签原值后才交HTTP做表单编码；不透传extra或卡券参数。
     * Ticket工厂负责MOBILE原目标认证，调用方负责关闭Ticket；返回Map含目标，严禁日志输出。
     * 设计：docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#512-仅直充范围与请求装配2026-10-18。
     */
    public static Map<String, String> buildRequest(OperationTicket ticket, Instant timestamp) {
        if (ticket == null || timestamp == null || ticket.resolvedCredentialMaterial() == null
                || ticket.merchantAccount() == null || ticket.merchantAccount().isBlank()) {
            throw new IllegalArgumentException("仁跃请求材料不完整");
        }
        boolean create = ticket.operationKind() == FulfillmentOperationKind.FULFILL && ticket.adapterStepCode() == AdapterStepCode.SEND;
        boolean query = ticket.operationKind() == FulfillmentOperationKind.QUERY_ORDER && ticket.adapterStepCode() == AdapterStepCode.QUERY_ORDER;
        if (!create && !query) throw new IllegalArgumentException("仁跃只支持直充创建与原单查询");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("MerchantNo", ticket.merchantAccount());
        fields.put("ThirdOrderNo", ticket.billno());
        fields.put("TimeStamp", Long.toString(timestamp.getEpochSecond()));
        if (create) {
            if (ticket.quantity() != 1 || ticket.settlementUnitPriceMinorUnits() == null
                    || ticket.settlementUnitPriceMinorUnits() < 0 || !Money.CURRENCY_CNY.equals(ticket.currency())) {
                throw new IllegalArgumentException("仁跃直充要求单份及非负CNY整分供价");
            }
            for (String value : new String[]{ticket.externalSkuCode(), ticket.rechargeAccount(), ticket.callbackUrl()}) {
                if (value == null || value.isBlank()) throw new IllegalArgumentException("仁跃直充字段不完整");
            }
            fields.put("ProductCode", ticket.externalSkuCode());
            fields.put("RechargeNum", Long.toString(ticket.quantity()));
            fields.put("RechargeNo", ticket.rechargeAccount());
            fields.put("AgreedPrice", BigDecimal.valueOf(ticket.settlementUnitPriceMinorUnits(), 2).toPlainString());
            fields.put("CallbackUrl", ticket.callbackUrl());
        }
        fields.put("SignSecret", signRequest(fields, ticket.resolvedCredentialMaterial()));
        return Map.copyOf(fields);
    }

    /**
     * 签原始值而非表单编码值；非空空格参与签名，不trim/casefold。只负责请求密码合同，见设计§5.1.1。
     * merchantKey以可擦除UTF-8字节更新签名，不将其拼入不可变规范串；返回值仅为Base64签名。
     */
    public static String signRequest(Map<String, String> fields, ResolvedCredentialMaterial material) {
        try {
            if (fields == null || material == null) throw new IllegalArgumentException("缺少请求材料");
            Map<String, String> sorted = new TreeMap<>();
            fields.forEach((name, value) -> {
                if (name == null || name.isEmpty() || name.equals("MerchantKey")
                        || name.chars().anyMatch(c -> c < '!' || c > '~' || c == '&' || c == '=')) {
                    throw new IllegalArgumentException("请求字段名无效");
                }
                if (!EXCLUDED_REQUEST_FIELDS.contains(name) && value != null && !value.isEmpty()) sorted.put(name, value);
            });
            if (sorted.isEmpty()) throw new IllegalArgumentException("缺少请求字段");
            String canonical = sorted.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("&"));
            return material.use(PRIVATE_KEY, privateChars -> material.use(MERCHANT_KEY, merchantChars -> {
                byte[] merchantBytes = utf8(merchantChars);
                byte[] requestBytes = canonical.getBytes(StandardCharsets.UTF_8);
                try {
                    Signature signer = Signature.getInstance("SHA256withRSA");
                    signer.initSign(loadPrivateKey(privateChars));
                    signer.update(requestBytes);
                    signer.update(MERCHANT_KEY_SUFFIX);
                    signer.update(merchantBytes);
                    return Base64.getEncoder().encodeToString(signer.sign());
                } catch (GeneralSecurityException failure) {
                    throw new IllegalArgumentException("RSA签名运算失败", failure);
                } finally {
                    Arrays.fill(merchantBytes, (byte) 0);
                    Arrays.fill(requestBytes, (byte) 0);
                }
            }));
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw new IllegalArgumentException("仁跃请求签名失败", failure);
        }
    }

    /**
     * 逐块解密并将完整明文字节所有权交给调用方，调用方必须finally擦除；中途失败不返回前半份。
     * 解密不是认证：本方法不比较字段集/大小写，不构造VERIFIED事实，详见设计§5.1.1。
     */
    public static byte[] decryptReceipt(String receipt, ResolvedCredentialMaterial material) {
        try {
            if (material == null) throw new IllegalArgumentException("缺少回执材料");
            return material.use(PRIVATE_KEY, privateChars -> {
                byte[] encrypted = decodeBase64(receipt);
                byte[] workspace = new byte[encrypted.length];
                try {
                    PrivateKey key = loadPrivateKey(privateChars);
                    int blockSize = ((((RSAKey) key).getModulus().bitLength()) + 7) / 8;
                    if (encrypted.length % blockSize != 0) throw new IllegalArgumentException("RSA回执块长度无效");
                    Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                    cipher.init(Cipher.DECRYPT_MODE, key);
                    int written = 0;
                    for (int offset = 0; offset < encrypted.length; offset += blockSize) {
                        byte[] block = cipher.doFinal(encrypted, offset, blockSize);
                        try {
                            System.arraycopy(block, 0, workspace, written, block.length);
                            written += block.length;
                        } finally { Arrays.fill(block, (byte) 0); }
                    }
                    return Arrays.copyOf(workspace, written);
                } catch (GeneralSecurityException failure) {
                    throw new IllegalArgumentException("RSA解密运算失败", failure);
                } finally {
                    Arrays.fill(encrypted, (byte) 0);
                    Arrays.fill(workspace, (byte) 0);
                }
            });
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw new IllegalArgumentException("仁跃回执解密失败", failure);
        }
    }

    /** 仅PKCS#8；拒绝猜测PKCS#1容器，临时DER擦除，不承诺JCA对象/不可变String立即擦除。 */
    private static PrivateKey loadPrivateKey(char[] chars) throws GeneralSecurityException {
        String content = new String(chars).strip();
        if (content.startsWith(PEM_BEGIN) && content.endsWith(PEM_END)) {
            content = content.substring(PEM_BEGIN.length(), content.length() - PEM_END.length());
        } else if (content.contains("-----")) {
            throw new IllegalArgumentException("私钥PEM类型或边界无效");
        }
        byte[] der = decodeBase64(content);
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } finally { Arrays.fill(der, (byte) 0); }
    }

    /** 严格Base64加有限ASCII排版空白，不用会静默忽略任意垃圾字符的MIME解码器。 */
    private static byte[] decodeBase64(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_ENCODED_LENGTH) {
            throw new IllegalArgumentException("Base64长度无效");
        }
        StringBuilder compact = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') continue;
            if (!(c >= 'A' && c <= 'Z') && !(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9')
                    && c != '+' && c != '/' && c != '=') throw new IllegalArgumentException("Base64字符无效");
            compact.append(c);
        }
        if (compact.isEmpty()) throw new IllegalArgumentException("Base64内容为空");
        return Base64.getDecoder().decode(compact.toString());
    }

    private static byte[] utf8(char[] chars) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(chars));
            try {
                byte[] result = new byte[encoded.remaining()];
                encoded.get(result);
                return result;
            } finally { if (encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0); }
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("凭据UTF-8编码无效", failure);
        }
    }
}
