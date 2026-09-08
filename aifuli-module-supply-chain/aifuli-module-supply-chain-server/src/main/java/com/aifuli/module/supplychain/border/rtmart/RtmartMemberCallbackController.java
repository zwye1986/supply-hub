package com.aifuli.module.supplychain.border.rtmart;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.api.instore.dto.InstoreMemberRespDTO;
import com.aifuli.module.supplychain.application.facade.InstoreChannelMemberFacade;
import com.aifuli.module.supplychain.application.facade.InstoreChannelMemberFacade.ResolveMemberCommand;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.security.PermitAll;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.PublicKey;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.aifuli.module.supplychain.border.rtmart.RtmartResponseCode.INVALID_APP_KEY;
import static com.aifuli.module.supplychain.border.rtmart.RtmartResponseCode.INVALID_ARGUMENT;
import static com.aifuli.module.supplychain.border.rtmart.RtmartResponseCode.INVALID_SIGNATURE;
import static com.aifuli.module.supplychain.border.rtmart.RtmartResponseCode.REQUEST_EXPIRED;
import static com.aifuli.module.supplychain.border.rtmart.RtmartResponseCode.SYSTEM_ERROR;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.*;

/**
 * 旅通"获取会员信息"入站适配器：接收旅通 HTTP 请求，验签后调中台会员解析 Facade，
 * 将结果映射为旅通协议格式返回。
 *
 * <p>旅通协议入站签名与出站一致为 SHA1withRSA（旅通私钥签名、本端以旅通公钥验签，
 * 标准 Base64），响应格式为 {@code result/code/msg/data}，与中台 CommonResult 响应不同。
 * 日志禁止输出 token、手机号、姓名等个人信息。
 * 设计见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#6-渠道服务}。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/rtmart")
@RequiredArgsConstructor
public class RtmartMemberCallbackController {

    private final RtmartInboundProperties properties;
    private final InstoreChannelMemberFacade memberFacade;

    private PublicKey verifyKey;

    /**
     * 启用状态下启动即解析旅通验签公钥：配置非法直接阻止应用启动（fail-fast），
     * 公钥常驻内存供每次请求验签，不在请求路径上重复解析。
     */
    @PostConstruct
    void initializeVerifyKey() {
        if (properties.isEnabled()) {
            this.verifyKey = RtmartPublicKeyParser.parseX509(properties.getPublicKey());
        }
    }

    @PostMapping("/member/resolve")
    @PermitAll
    public RtmartMemberResponse resolveMember(@RequestBody RtmartMemberRequest request) {
        if (!properties.isEnabled()) {
            log.warn("[RTMART][inbound] adapter disabled");
            return RtmartMemberResponse.fail(SYSTEM_ERROR, "服务未开启");
        }

        if (isBlank(request.getSign())) {
            log.warn("[RTMART][inbound] signature missing");
            return RtmartMemberResponse.fail(INVALID_SIGNATURE, "签名不能为空");
        }

        if (isBlank(request.getAppKey()) || isBlank(request.getVersion())
                || request.getTimestamp() == null || isBlank(request.getNonceStr())
                || isBlank(request.getToken())) {
            log.warn("[RTMART][inbound] missing required fields");
            return RtmartMemberResponse.fail(INVALID_ARGUMENT, "请求参数缺失");
        }

        if (!properties.getAppKey().equals(request.getAppKey())) {
            log.warn("[RTMART][inbound] app_key mismatch");
            return RtmartMemberResponse.fail(INVALID_APP_KEY, "app_key 不正确");
        }

        long nowSeconds = System.currentTimeMillis() / 1000;
        if (Math.abs(nowSeconds - request.getTimestamp()) > properties.getTimestampToleranceSeconds()) {
            log.warn("[RTMART][inbound] timestamp out of tolerance, requestTs={}, serverTs={}",
                    request.getTimestamp(), nowSeconds);
            return RtmartMemberResponse.fail(REQUEST_EXPIRED, "请求已过期");
        }

        Map<String, String> signFields = new LinkedHashMap<>();
        signFields.put("app_key", request.getAppKey());
        signFields.put("version", request.getVersion());
        signFields.put("timestamp", String.valueOf(request.getTimestamp()));
        signFields.put("nonce_str", request.getNonceStr());
        signFields.put("token", request.getToken());

        if (!RtmartSignVerifier.verify(signFields, request.getSign(), verifyKey)) {
            log.warn("[RTMART][inbound] signature verification failed, nonceHash={}",
                    nonceHashPrefix(request.getNonceStr()));
            return RtmartMemberResponse.fail(INVALID_SIGNATURE, "签名验证失败");
        }

        String internalToken = stripCxbPrefix(request.getToken());

        log.info("[RTMART][inbound] resolveMember entry projectId={}, channelCode={}",
                properties.getProjectId(), properties.getChannelCode());

        try {
            InstoreMemberRespDTO member = memberFacade.resolveMember(
                    new ResolveMemberCommand(properties.getProjectId(),
                            properties.getChannelCode(), internalToken));

            RtmartMemberData data = new RtmartMemberData();
            data.setUserCode(member.getUserCode());
            data.setMobile(member.getMobile());
            data.setUserName(member.getUserName());
            data.setCompanyCode(member.getCompanyCode());

            log.info("[RTMART][inbound] resolveMember success, companyCode={}", member.getCompanyCode());
            return RtmartMemberResponse.success(data);

        } catch (ServiceException exception) {
            log.warn("[RTMART][inbound] resolveMember failed, code={}, message={}",
                    exception.getCode(), exception.getMessage());
            return mapServiceException(exception);
        } catch (RuntimeException exception) {
            log.error("[RTMART][inbound] resolveMember unexpected error", exception);
            return RtmartMemberResponse.fail(SYSTEM_ERROR, "系统错误");
        }
    }

    private static RtmartMemberResponse mapServiceException(ServiceException exception) {
        Integer code = exception.getCode();
        if (code != null && (code.equals(INSTORE_CREDENTIAL_INVALID.getCode())
                || code.equals(INSTORE_CREDENTIAL_EXPIRED.getCode())
                || code.equals(INSTORE_CREDENTIAL_ALREADY_USED.getCode())
                || code.equals(INSTORE_CREDENTIAL_REVOKED.getCode())
                || code.equals(INSTORE_TENANT_SCOPE_CONFLICT.getCode())
                || code.equals(INSTORE_CONCURRENT_UPDATE.getCode())
                || code.equals(INSTORE_MEMBER_PROFILE_INVALID.getCode()))) {
            return RtmartMemberResponse.fail(INVALID_ARGUMENT, "授权凭证或会员资料无效");
        }
        return RtmartMemberResponse.fail(SYSTEM_ERROR,
                code != null && code.equals(INSTORE_MEMBER_PROFILE_UNAVAILABLE.getCode())
                        ? "会员资料暂时不可用" : "系统错误");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String stripCxbPrefix(String token) {
        if (token != null && token.startsWith("CXB_")) {
            return token.substring(4);
        }
        return token;
    }

    private static String nonceHashPrefix(String nonce) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((nonce == null ? "" : nonce).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
