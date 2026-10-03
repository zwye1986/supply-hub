package com.aifuli.module.supplychain.supplieradapter.huasheng;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 华胜通信密码协议的无状态 JCA 工具，不持有凭据、不访问业务状态，也不记录敏感数据。
 * AES 正文以 appSecret 的 UTF-8 字节为密钥、appId 的 UTF-8 字节为 IV；签名原文始终是传入的
 * 密文字符串原始 UTF-8 字节。每次调用创建独立 Cipher/Signature，避免跨请求复用可变密码对象。
 * 加解密、签名和密钥加载错误使用固定说明并保留原因；验签无效输入按协议返回 false。
 * 设计见 docs/superpowers/specs/2026-10-02-huasheng-fulfillment-adapter-design.md#121-第一切片通信密码协议。
 */
public final class HuashengCrypto {

    private static final String AES_ALGORITHM = "AES";
    private static final String AES_TRANSFORMATION = "AES/CBC/PKCS5Padding";
    private static final String RSA_ALGORITHM = "RSA";
    private static final String SIGNATURE_ALGORITHM = "SHA512withRSA";
    private static final int AES_BLOCK_BYTES = 16;
    private static final int AES_192_KEY_BYTES = 24;
    private static final int AES_256_KEY_BYTES = 32;
    private static final String PRIVATE_KEY_PEM_TYPE = "PRIVATE KEY";
    private static final String PUBLIC_KEY_PEM_TYPE = "PUBLIC KEY";
    private static final String RSA_PUBLIC_KEY_PEM_TYPE = "RSA PUBLIC KEY";
    private static final HexFormat HEX = HexFormat.of();

    // SubjectPublicKeyInfo 的 rsaEncryption AlgorithmIdentifier（OID 1.2.840.113549.1.1.1 + NULL）。
    private static final byte[] RSA_ALGORITHM_IDENTIFIER = HEX.parseHex("300d06092a864886f70d0101010500");
    private static final int DER_SEQUENCE_TAG = 0x30;
    private static final int DER_BIT_STRING_TAG = 0x03;
    private static final int DER_LONG_LENGTH_FLAG = 0x80;

    private HuashengCrypto() {
    }

    /**
     * 加密非空正文，返回小写 hex。密钥和 IV 按 UTF-8 字节长度校验，不能以字符数代替。
     * 设计见 docs/superpowers/specs/2026-10-02-huasheng-fulfillment-adapter-design.md#121-第一切片通信密码协议。
     */
    public static String encryptBodyHex(String plain, String secret, String appId) {
        try {
            requireText(plain);
            Cipher cipher = createAesCipher(Cipher.ENCRYPT_MODE, secret, appId);
            return HEX.formatHex(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("华胜正文加密失败", exception);
        }
    }

    /**
     * 严格解码 ASCII hex 并解密；接受大小写 hex，拒绝空白、非 hex、奇数长度及非法 AES 块。
     * 该方法负责密文格式校验，不得在验签前用它归一化原始正文。
     * 设计见 docs/superpowers/specs/2026-10-02-huasheng-fulfillment-adapter-design.md#121-第一切片通信密码协议。
     */
    public static String decryptBodyHex(String hex, String secret, String appId) {
        try {
            Cipher cipher = createAesCipher(Cipher.DECRYPT_MODE, secret, appId);
            return new String(cipher.doFinal(decodeHex(hex)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("华胜正文解密失败", exception);
        }
    }

    /**
     * 对原始正文字符串的 UTF-8 字节做 SHA512withRSA 签名，返回小写 hex 签名。
     * 此处只拒绝缺失正文，不解码或归一化正文：大小写和空白均参与签名，密文格式由加解密边界校验。
     * 设计见 docs/superpowers/specs/2026-10-02-huasheng-fulfillment-adapter-design.md#121-第一切片通信密码协议。
     */
    public static String signBodyHex(String hex, PrivateKey key) {
        try {
            requireText(hex);
            if (key == null) {
                throw new IllegalArgumentException("缺少签名密钥");
            }
            Signature signer = Signature.getInstance(SIGNATURE_ALGORITHM);
            signer.initSign(key);
            signer.update(hex.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(signer.sign());
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("华胜正文签名失败", exception);
        }
    }

    /**
     * 验证收到的正文原始 UTF-8 字节；签名 hex 严格解码，正文大小写及空白不归一化。
     * 缺字段、非法签名、篡改和错误公钥均为验签不通过；调用方须在通过后再严格解密正文。
     * 设计见 docs/superpowers/specs/2026-10-02-huasheng-fulfillment-adapter-design.md#121-第一切片通信密码协议。
     */
    public static boolean verifyBodyHex(String hex, String signHex, PublicKey key) {
        if (hex == null || hex.isBlank() || key == null) {
            return false;
        }
        try {
            byte[] signature = decodeHex(signHex);
            Signature verifier = Signature.getInstance(SIGNATURE_ALGORITHM);
            verifier.initVerify(key);
            verifier.update(hex.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(signature);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            // 协议将格式错误、密钥不匹配等输入失败映射为 false，不输出正文、签名或密钥。
            return false;
        }
    }

    /**
     * 只加载 RSA PKCS#8 私钥，支持 PRIVATE KEY PEM 或裸 Base64。
     * 明确拒绝 RSA PRIVATE KEY（PKCS#1）等错误 PEM 类型，不对私钥容器做猜测或伪包装。
     * 设计见 docs/superpowers/specs/2026-10-02-huasheng-fulfillment-adapter-design.md#121-第一切片通信密码协议。
     */
    public static PrivateKey loadPkcs8PrivateKey(String key) {
        try {
            byte[] encoded = decodeKey(key, PRIVATE_KEY_PEM_TYPE);
            return KeyFactory.getInstance(RSA_ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(encoded));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("华胜私钥加载失败", exception);
        }
    }

    /**
     * 加载 RSA 回调公钥：PUBLIC KEY PEM/裸 Base64 按 X.509 解析，RSA PUBLIC KEY PEM 按
     * PKCS#1 包装为 X.509 SubjectPublicKeyInfo 后交给 JCA 校验，不引入第三方密码或 ASN.1 库。
     * 设计见 docs/superpowers/specs/2026-10-02-huasheng-fulfillment-adapter-design.md#121-第一切片通信密码协议。
     */
    public static PublicKey loadPublicKey(String key) {
        try {
            requireText(key);
            byte[] encoded;
            if (key.strip().startsWith(pemBegin(RSA_PUBLIC_KEY_PEM_TYPE))) {
                encoded = wrapPkcs1PublicKey(decodeKey(key, RSA_PUBLIC_KEY_PEM_TYPE));
            } else {
                encoded = decodeKey(key, PUBLIC_KEY_PEM_TYPE);
            }
            return KeyFactory.getInstance(RSA_ALGORITHM).generatePublic(new X509EncodedKeySpec(encoded));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("华胜公钥加载失败", exception);
        }
    }

    private static Cipher createAesCipher(int mode, String secret, String appId) throws GeneralSecurityException {
        requireText(secret);
        requireText(appId);
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        byte[] ivBytes = appId.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length != AES_BLOCK_BYTES && keyBytes.length != AES_192_KEY_BYTES && keyBytes.length != AES_256_KEY_BYTES) {
            throw new IllegalArgumentException("AES 密钥字节长度非法");
        }
        if (ivBytes.length != AES_BLOCK_BYTES) {
            throw new IllegalArgumentException("AES IV 字节长度非法");
        }
        Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
        cipher.init(mode, new SecretKeySpec(keyBytes, AES_ALGORITHM), new IvParameterSpec(ivBytes));
        return cipher;
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少必填密码协议参数");
        }
    }

    /** 提前检查 ASCII hex，固定错误内容，避免底层解码异常携带输入片段。 */
    private static byte[] decodeHex(String value) {
        requireText(value);
        if ((value.length() & 1) != 0) {
            throw new IllegalArgumentException("hex 长度非法");
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (!(character >= '0' && character <= '9')
                    && !(character >= 'a' && character <= 'f')
                    && !(character >= 'A' && character <= 'F')) {
                throw new IllegalArgumentException("hex 编码非法");
            }
        }
        return HEX.parseHex(value);
    }

    /** 只接受指定 PEM 首尾标记或裸 Base64；允许密钥排版空白，但不使用忽略任意字符的 MIME 解码器。 */
    private static byte[] decodeKey(String key, String pemType) {
        requireText(key);
        String content = key.strip();
        String begin = pemBegin(pemType);
        String end = "-----END " + pemType + "-----";
        if (content.startsWith(begin) && content.endsWith(end)) {
            content = content.substring(begin.length(), content.length() - end.length());
        } else if (content.contains("-----")) {
            throw new IllegalArgumentException("密钥 PEM 类型或边界非法");
        }
        content = content.replaceAll("\\s", "");
        requireText(content);
        return Base64.getDecoder().decode(content);
    }

    private static String pemBegin(String type) {
        return "-----BEGIN " + type + "-----";
    }

    /**
     * PKCS#1 RSAPublicKey 嵌入 BIT STRING（首字节表示零个未使用位），外层为 X.509 SPKI SEQUENCE。
     * 内部 RSA 结构仍由 JCA 解析验证；本方法仅组装标准容器，不解析或改变 modulus/exponent。
     */
    private static byte[] wrapPkcs1PublicKey(byte[] pkcs1) {
        ByteArrayOutputStream bitString = new ByteArrayOutputStream();
        bitString.write(0);
        bitString.writeBytes(pkcs1);
        ByteArrayOutputStream subjectPublicKeyInfo = new ByteArrayOutputStream();
        subjectPublicKeyInfo.writeBytes(RSA_ALGORITHM_IDENTIFIER);
        subjectPublicKeyInfo.writeBytes(encodeDer(DER_BIT_STRING_TAG, bitString.toByteArray()));
        return encodeDer(DER_SEQUENCE_TAG, subjectPublicKeyInfo.toByteArray());
    }

    /** DER 长度按最短大端编码输出，同时覆盖 RSA 2048/3072/4096 位公钥的长格式长度。 */
    private static byte[] encodeDer(int tag, byte[] content) {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        encoded.write(tag);
        if (content.length < DER_LONG_LENGTH_FLAG) {
            encoded.write(content.length);
        } else {
            int lengthBytes = (Integer.SIZE - Integer.numberOfLeadingZeros(content.length) + Byte.SIZE - 1) / Byte.SIZE;
            encoded.write(DER_LONG_LENGTH_FLAG | lengthBytes);
            for (int i = lengthBytes - 1; i >= 0; i--) {
                encoded.write(content.length >>> (i * Byte.SIZE));
            }
        }
        encoded.writeBytes(content);
        return encoded.toByteArray();
    }
}
