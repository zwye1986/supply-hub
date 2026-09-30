package com.aifuli.channel.taobaoflash.service;

import com.aifuli.channel.taobaoflash.dto.ChannelH5UrlResponse;
import com.aifuli.channel.taobaoflash.dto.ChannelSsoH5UrlRequest;
import eleme.openapi.h5.sdk.login.utils.JWTUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 渠道登录与 SSO 免登录服务：迁移自原 yudao-module-taobao LoginService 的 SSO 路径。
 *
 * <p>核心流程：校验 SSO 开关 → 校验时间戳 → HMAC 验签 → nonce 防重放 → 以 externalUserId 作 open_id
 * 生成 ele.me JWT → 拼装 H5 URL。盒马与淘宝闪购的区别仅在 H5 base URL，通过 variant=hema 切换。
 * 详见 docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.7。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelLoginService {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ENV_PROD = "PROD";
    private static final String ENV_PPE = "PPE";
    private static final String VARIANT_HEMA = "hema";

    private final RedissonClient redissonClient;

    @Value("${tbsg.sso.enabled:false}")
    private boolean ssoEnabled;

    @Value("${tbsg.sso.secret:}")
    private String ssoSecret;

    @Value("${tbsg.sso.timestamp-skew-seconds:300}")
    private long ssoTimestampSkewSeconds;

    @Value("${tbsg.sso.nonce-ttl-seconds:600}")
    private long ssoNonceTtlSeconds;

    @Value("${tbsg.sso.token-expire-days:7}")
    private long ssoTokenExpireDays;

    /** ele.me JWT 签发密钥（原 tbsg.login.consumerSecret），通过 Nacos 注入 */
    @Value("${tbsg.login.consumer-secret:}")
    private String consumerSecret;

    @Value("${tbsg.entry.from:aifuli}")
    private String from;

    @Value("${tbsg.entry.opensite-source:aifuli}")
    private String openSiteSourceCode;

    @Value("${tbsg.entry.welfare-3pp:aifuli}")
    private String welfare3pp;

    @Value("${tbsg.entry.h5-url-default:https://tb.ele.me/wow/alsc/mod/69958d4a14beac9d0c5bbbc7/}")
    private String h5UrlDefault;

    @Value("${tbsg.entry.h5-url-hema:https://tb.ele.me/wow/alsc/mod/hema/}")
    private String h5UrlHema;

    @Value("${tbsg.entry.mock-h5-base-url:}")
    private String mockH5BaseUrl;

    /**
     * 外部商城 SSO 免登录生成淘宝闪购 H5 链接。
     *
     * <p>不变量：时间窗、HMAC 签名、nonce 一次性必须全部通过才签发 JWT。
     * 日志不输出签名、密钥、JWT 明文与完整手机号。详见
     * {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.7}。</p>
     */
    public ChannelH5UrlResponse generateH5UrlBySso(ChannelSsoH5UrlRequest request) {
        log.info("[SSO免登录] 入口 externalUserId={}, env={}, variant={}, timestamp={}",
                request.getExternalUserId(), request.getEnv(), request.getVariant(), request.getTimestamp());
        if (!ssoEnabled) {
            log.warn("[SSO免登录] 未启用");
            return new ChannelH5UrlResponse("", "FAIL", "", "SSO免登录未启用");
        }
        if (!StringUtils.hasText(ssoSecret)) {
            log.error("[SSO免登录] 配置异常，tbsg.sso.secret 为空");
            return new ChannelH5UrlResponse("", "FAIL", "", "系统未完成SSO配置");
        }

        String env = normalizeEnv(request.getEnv());
        if (env == null) {
            return new ChannelH5UrlResponse("", "FAIL", "", "未知的环境参数");
        }

        String externalUserId = request.getExternalUserId().trim();
        String mobile = request.getMobile().trim();
        String nonce = request.getNonce().trim();
        long timestamp = request.getTimestamp();

        long nowSeconds = System.currentTimeMillis() / 1000;
        if (Math.abs(nowSeconds - timestamp) > ssoTimestampSkewSeconds) {
            return new ChannelH5UrlResponse("", "FAIL", "", "请求已过期，请重新发起");
        }

        String signPayload = externalUserId + "|" + mobile + "|" + env + "|" + timestamp + "|" + nonce;
        String expectedSign = hmacSha256Hex(signPayload, ssoSecret);
        if (!constantTimeEqualsIgnoreCase(expectedSign, request.getSign())) {
            log.warn("[SSO免登录] 签名校验失败, externalUserId={}", externalUserId);
            return new ChannelH5UrlResponse("", "FAIL", "", "签名校验失败");
        }

        if (!recordNonceOnce(nonce, externalUserId, timestamp)) {
            return new ChannelH5UrlResponse("", "FAIL", "", "请求重复，请勿重放");
        }

        long effectiveExpireDays = ssoTokenExpireDays > 0 ? ssoTokenExpireDays : 7L;
        Date expiresAt = new Date(System.currentTimeMillis() + effectiveExpireDays * 24L * 60L * 60L * 1000L);
        String token = buildTbsgJwtToken(mobile, externalUserId, expiresAt);
        boolean isPpe = ENV_PPE.equals(env);
        String h5Url = buildH5Url(token, isPpe, request.getVariant());
        log.info("[SSO免登录] 成功 externalUserId={}, env={}, variant={}, h5UrlPresent={}",
                externalUserId, env, request.getVariant(), h5Url != null && !h5Url.isBlank());
        return new ChannelH5UrlResponse(h5Url, "SUCCESS", BEARER_PREFIX + token, "");
    }

    private String buildTbsgJwtToken(String mobile, String openId, Date expiresAt) {
        Map<String, Object> params = new HashMap<>();
        params.put("mobile", mobile);
        params.put("open_id", openId);
        params.put("source", openSiteSourceCode);
        return JWTUtil.buildToken(params, consumerSecret, expiresAt);
    }

    private String buildH5Url(String token, boolean isPpe, String variant) {
        String baseUrl = StringUtils.hasText(mockH5BaseUrl)
                ? mockH5BaseUrl.trim()
                : (VARIANT_HEMA.equalsIgnoreCase(variant) ? h5UrlHema : h5UrlDefault);

        StringBuilder urlBuilder = new StringBuilder(baseUrl);
        appendQueryParam(urlBuilder, "from", from);
        appendQueryParam(urlBuilder, "opensite_source", openSiteSourceCode);
        appendQueryParam(urlBuilder, "welfare_3pp", welfare3pp);
        appendQueryParam(urlBuilder, "hideEleTitle", "1");
        appendQueryParam(urlBuilder, "hide-tabbar", "1");
        appendQueryParam(urlBuilder, "jwt", token);
        if (isPpe) {
            appendQueryParam(urlBuilder, "env", "ppe");
        }
        return urlBuilder.toString();
    }

    private void appendQueryParam(StringBuilder urlBuilder, String name, String value) {
        if (urlBuilder.indexOf("?") < 0) {
            urlBuilder.append('?');
        } else {
            char lastChar = urlBuilder.charAt(urlBuilder.length() - 1);
            if (lastChar != '?' && lastChar != '&') {
                urlBuilder.append('&');
            }
        }
        urlBuilder.append(encodeUrlParam(name)).append('=').append(encodeUrlParam(value));
    }

    private String encodeUrlParam(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String normalizeEnv(String env) {
        if (!StringUtils.hasText(env)) {
            return null;
        }
        String normalized = env.trim().toUpperCase(Locale.ROOT);
        if (ENV_PROD.equals(normalized) || ENV_PPE.equals(normalized)) {
            return normalized;
        }
        return null;
    }

    private String hmacSha256Hex(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] result = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(result.length * 2);
            for (byte b : result) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("计算SSO签名失败", e);
        }
    }

    private boolean constantTimeEqualsIgnoreCase(String expected, String actual) {
        if (!StringUtils.hasText(expected) || !StringUtils.hasText(actual)) {
            return false;
        }
        byte[] expectedBytes = expected.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }

    private boolean recordNonceOnce(String nonce, String externalUserId, long timestamp) {
        String nonceKey = "tbsg:sso:nonce:" + nonce;
        RBucket<String> bucket = redissonClient.getBucket(nonceKey);
        String nonceValue = externalUserId + ":" + timestamp;
        return bucket.setIfAbsent(nonceValue, Duration.ofSeconds(ssoNonceTtlSeconds));
    }
}
