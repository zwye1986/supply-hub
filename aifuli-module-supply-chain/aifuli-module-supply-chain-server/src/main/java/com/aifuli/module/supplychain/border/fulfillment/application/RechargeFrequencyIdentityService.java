package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTargetFields;
import com.aifuli.module.supplychain.border.fulfillment.domain.RechargeFrequencyConstants;
import com.aifuli.module.supplychain.infrastructure.security.RechargeFrequencyIdentityKeyProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * 实际充值手机号稳定摘要。每次创建Mac实例，域隔离为用途码、零字节、规范化手机号。
 * 不复用项目密钥、履约目标指纹或buyerUserId，输出只保留摘要和脱敏值。
 * 设计见 {@code docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#3-持久化模型与不变量}。
 */
@Service
@RequiredArgsConstructor
public class RechargeFrequencyIdentityService {
    private final RechargeFrequencyIdentityKeyProvider keyProvider;

    public MobileIdentity identify(String actualMobile) {
        String mobile = FulfillmentTargetFields.normalizeMobile(actualMobile);
        if (mobile == null) { throw new IllegalArgumentException("直充限频MOBILE目标非法"); }
        byte[] key = keyProvider.identityKey();
        if (key == null || key.length != 32) { throw new IllegalStateException("直充限频身份密钥不可用"); }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(RechargeFrequencyConstants.HMAC_PURPOSE.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return new MobileIdentity(mac.doFinal(mobile.getBytes(StandardCharsets.UTF_8)),
                    mobile.substring(0, 3) + "****" + mobile.substring(7));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("直充限频身份摘要计算失败", exception);
        }
    }

    /** 无明文手机号的内部身份快照；防御复制避免队列身份被调用方篡改。 */
    public record MobileIdentity(byte[] mobileDigest, String mobileMasked) {
        public MobileIdentity { mobileDigest = mobileDigest.clone(); }
        @Override public byte[] mobileDigest() { return mobileDigest.clone(); }
    }
}
