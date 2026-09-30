package com.aifuli.module.supplychain.border.rtmart;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 旅通入站适配器配置：app_key 映射与 SHA1withRSA 验签公钥。
 *
 * <p>旅通回调商户接口①时携带 {@code app_key}，本配置将其映射为供应链内部
 * {@code projectId + channelCode}，并通过 {@code publicKey}（旅通提供的验签公钥，
 * X.509 PEM 或裸 Base64）验证 SHA1withRSA 签名。启用时启动阶段 fail-fast 校验全部
 * 必填项，阻止以无效配置注册服务发现。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "aifuli.supply-chain.instore.rtmart.inbound")
public class RtmartInboundProperties {

    private boolean enabled = false;

    /** 旅通请求中期望的 app_key 值。 */
    @ToString.Exclude
    private String appKey;

    /** 旅通验签公钥（X.509 PEM 或裸 Base64），只能经 {@link RtmartPublicKeyParser} 解析，禁止诊断输出。 */
    @ToString.Exclude
    private String publicKey;

    /** app_key 映射的供应链采购项目 ID。 */
    private Long projectId;

    /** app_key 映射的渠道编码。 */
    private String channelCode;

    /** 时间戳容差（秒），超出则拒绝请求。旅通协议要求 3 分钟。 */
    private int timestampToleranceSeconds = 180;

    @PostConstruct
    public void validate() {
        if (!enabled) {
            return;
        }
        requireText(appKey, "appKey");
        requireText(publicKey, "publicKey");
        requireText(channelCode, "channelCode");
        if (projectId == null || projectId <= 0) {
            throw new IllegalStateException("rtmart inbound projectId must be positive");
        }
        if (timestampToleranceSeconds <= 0) {
            throw new IllegalStateException("rtmart inbound timestampToleranceSeconds must be positive");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("rtmart inbound " + name + " must not be blank");
        }
    }
}
