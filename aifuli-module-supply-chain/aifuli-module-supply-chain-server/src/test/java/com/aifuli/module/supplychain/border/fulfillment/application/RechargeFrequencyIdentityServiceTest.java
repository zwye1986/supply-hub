package com.aifuli.module.supplychain.border.fulfillment.application;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static org.assertj.core.api.Assertions.*;

class RechargeFrequencyIdentityServiceTest {
    private final byte[] key = new byte[32];

    @Test
    void prefixesShareStableDigestAndDifferentMobilesDoNot() throws Exception {
        var service = service(key);
        var bare = identify(service, "13800138000");
        assertThat(digest(bare)).isEqualTo(digest(identify(service, "+8613800138000")))
                .isEqualTo(digest(identify(service, "008613800138000")))
                .isNotEqualTo(digest(identify(service, "13900139000")));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        mac.update("RECHARGE_FREQUENCY_MOBILE".getBytes(StandardCharsets.UTF_8));
        mac.update((byte) 0);
        assertThat(digest(bare)).isEqualTo(mac.doFinal("13800138000".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void identityIsMaskedAndDigestIsDefensivelyCopied() throws Exception {
        var identity = identify(service(key), "13800138000");
        assertThat(identity.toString()).doesNotContain("13800138000");
        assertThat(identity.mobileMasked()).isEqualTo("138****8000");
        byte[] original = digest(identity);
        byte[] mutated = digest(identity);
        Arrays.fill(mutated, (byte) 7);
        assertThat(digest(identity)).isEqualTo(original);
    }

    @Test
    void invalidMobileAndMissingKeyFailClosedWithoutSensitiveValue() throws Exception {
        var service = service(key);
        assertThatThrownBy(() -> identify(service, "private-invalid-mobile"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private-invalid-mobile");
        assertThatThrownBy(() -> identify(service(null), "13800138000"))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("13800138000");
    }

    @Test
    void requestDtoToStringDoesNotExposeActualMobile() {
        var target = new com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentTargetDTO();
        target.setValues(java.util.Map.of("accountType", "MOBILE", "accountValue", "13800138000"));
        var request = new com.aifuli.module.supplychain.api.fulfillment.dto.AcceptFulfillmentReqDTO();
        request.setTarget(target);
        // buyerUserId 已删除：防泄漏断言聚焦目标手机号与目标载体本身。
        assertThat(target.toString()).doesNotContain("13800138000");
        assertThat(request.toString()).doesNotContain("13800138000", "13900139000");
    }

    private RechargeFrequencyIdentityService service(byte[] value) {
        return new RechargeFrequencyIdentityService(() -> value);
    }
    private RechargeFrequencyIdentityService.MobileIdentity identify(RechargeFrequencyIdentityService service, String raw) {
        return service.identify(raw);
    }
    private byte[] digest(RechargeFrequencyIdentityService.MobileIdentity identity) {
        return identity.mobileDigest();
    }
}
