package com.aifuli.module.supplychain.border.rtmart;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.api.instore.dto.InstoreMemberRespDTO;
import com.aifuli.module.supplychain.application.facade.InstoreChannelMemberFacade;
import com.aifuli.module.supplychain.application.facade.InstoreChannelMemberFacade.ResolveMemberCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 旅通"获取会员信息"入站控制器测试：协议校验编排、SHA1withRSA 验签、CXB_ 前缀剥离与
 * 中台错误码 → 旅通错误码映射。密钥均在测试运行时生成，禁止使用真实渠道秘密。
 *
 * <p>覆盖范围见 {@code docs/integration/rtmart/instore-channel-member-resolve-api.html#5-错误码}。</p>
 */
@ExtendWith(MockitoExtension.class)
class RtmartMemberCallbackControllerTest {

    private static final String APP_KEY = "1G23d45P6";
    private static final long PROJECT_ID = 201L;
    private static final String CHANNEL_CODE = "RT_MART";

    @Mock
    private InstoreChannelMemberFacade memberFacade;

    private RtmartInboundProperties properties;
    private RtmartMemberCallbackController controller;
    private KeyPair keyPair;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();

        properties = new RtmartInboundProperties();
        properties.setEnabled(true);
        properties.setAppKey(APP_KEY);
        properties.setPublicKey(Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        properties.setProjectId(PROJECT_ID);
        properties.setChannelCode(CHANNEL_CODE);
        properties.setTimestampToleranceSeconds(300);

        controller = new RtmartMemberCallbackController(properties, memberFacade);
        controller.initializeVerifyKey();
    }

    @Test
    void disabledAdapterFailsClosedWithoutTouchingFacade() {
        properties.setEnabled(false);

        RtmartMemberResponse response = controller.resolveMember(validSignedRequest("tk-abc"));

        assertThat(response.getResult()).isEqualTo("fail");
        assertThat(response.getCode()).isEqualTo("E5000");
        assertThat(response.getMsg()).isEqualTo("服务未开启");
        verify(memberFacade, never()).resolveMember(any());
    }

    @Test
    void missingRequiredFieldsRejected() {
        RtmartMemberRequest request = validSignedRequest("tk-abc");
        request.setToken(null);

        RtmartMemberResponse response = controller.resolveMember(request);

        assertThat(response.getCode()).isEqualTo("E4001");
        assertThat(response.getMsg()).isEqualTo("请求参数缺失");
        verify(memberFacade, never()).resolveMember(any());
    }

    @Test
    void appKeyMismatchRejected() {
        RtmartMemberRequest request = validSignedRequest("tk-abc");
        request.setAppKey("wrong-app-key");

        RtmartMemberResponse response = controller.resolveMember(request);

        assertThat(response.getCode()).isEqualTo("E4003");
        assertThat(response.getMsg()).isEqualTo("app_key 不正确");
        verify(memberFacade, never()).resolveMember(any());
    }

    @Test
    void timestampOutsideToleranceRejected() {
        RtmartMemberRequest request = validSignedRequest("tk-abc");
        long staleTimestamp = System.currentTimeMillis() / 1000 - 301;
        request.setTimestamp(staleTimestamp);
        request.setSign(sign(request.getAppKey(), request.getVersion(), staleTimestamp,
                request.getNonceStr(), request.getToken()));

        RtmartMemberResponse response = controller.resolveMember(request);

        assertThat(response.getCode()).isEqualTo("E4002");
        assertThat(response.getMsg()).isEqualTo("请求已过期");
        verify(memberFacade, never()).resolveMember(any());
    }

    @Test
    void invalidSignatureRejectedBeforeCredentialClaim() {
        RtmartMemberRequest request = validSignedRequest("tk-abc");
        request.setSign("INVALID-SIGNATURE-VALUE==");

        RtmartMemberResponse response = controller.resolveMember(request);

        assertThat(response.getCode()).isEqualTo("E4004");
        assertThat(response.getMsg()).isEqualTo("签名验证失败");
        verify(memberFacade, never()).resolveMember(any());
    }

    @Test
    void signatureTamperingRejected() {
        RtmartMemberRequest request = validSignedRequest("tk-original");
        request.setToken("tk-tampered");

        RtmartMemberResponse response = controller.resolveMember(request);

        assertThat(response.getCode()).isEqualTo("E4004");
        assertThat(response.getMsg()).isEqualTo("签名验证失败");
        verify(memberFacade, never()).resolveMember(any());
    }

    @Test
    void resolvesMemberAndStripsCxbPrefixAfterVerifyingOriginalToken() {
        RtmartMemberRequest request = validSignedRequest("CXB_tk-abc");
        when(memberFacade.resolveMember(any())).thenReturn(member("247", "15601691399", "啦啦啦", "X0000A"));

        RtmartMemberResponse response = controller.resolveMember(request);

        assertThat(response.getResult()).isEqualTo("success");
        assertThat(response.getCode()).isEqualTo("E0000");
        assertThat(response.getMsg()).isEqualTo("");
        RtmartMemberData data = (RtmartMemberData) response.getData();
        assertThat(data.getUserCode()).isEqualTo("247");
        assertThat(data.getMobile()).isEqualTo("15601691399");
        assertThat(data.getUserName()).isEqualTo("啦啦啦");
        assertThat(data.getCompanyCode()).isEqualTo("X0000A");

        ArgumentCaptor<ResolveMemberCommand> captor = ArgumentCaptor.forClass(ResolveMemberCommand.class);
        verify(memberFacade).resolveMember(captor.capture());
        assertThat(captor.getValue().projectId()).isEqualTo(PROJECT_ID);
        assertThat(captor.getValue().channelCode()).isEqualTo(CHANNEL_CODE);
        assertThat(captor.getValue().token()).isEqualTo("tk-abc");
    }

    @Test
    void tokenWithoutPrefixPassesThroughUnchanged() {
        RtmartMemberRequest request = validSignedRequest("tk-abc");
        when(memberFacade.resolveMember(any())).thenReturn(member("247", "15601691399", null, "X0000A"));

        RtmartMemberResponse response = controller.resolveMember(request);

        assertThat(response.getCode()).isEqualTo("E0000");
        ArgumentCaptor<ResolveMemberCommand> captor = ArgumentCaptor.forClass(ResolveMemberCommand.class);
        verify(memberFacade).resolveMember(captor.capture());
        assertThat(captor.getValue().token()).isEqualTo("tk-abc");
    }

    @Test
    void mapsInvalidCredentialServiceExceptionToInvalidArgument() {
        RtmartMemberRequest request = validSignedRequest("tk-used");
        when(memberFacade.resolveMember(any()))
                .thenThrow(new ServiceException(1_050_001_038, "凭证已被消费"));

        RtmartMemberResponse response = controller.resolveMember(request);

        assertThat(response.getResult()).isEqualTo("fail");
        assertThat(response.getCode()).isEqualTo("E4001");
        assertThat(response.getMsg()).isEqualTo("授权凭证或会员资料无效");
    }

    @Test
    void mapsMemberUnavailableServiceExceptionToE5000() {
        RtmartMemberRequest request = validSignedRequest("tk-abc");
        when(memberFacade.resolveMember(any()))
                .thenThrow(new ServiceException(1_050_001_045, "会员资料暂时不可用"));

        RtmartMemberResponse response = controller.resolveMember(request);

        assertThat(response.getResult()).isEqualTo("fail");
        assertThat(response.getCode()).isEqualTo("E5000");
        assertThat(response.getMsg()).isEqualTo("会员资料暂时不可用");
    }

    @Test
    void mapsUnexpectedRuntimeErrorToSystemError() {
        RtmartMemberRequest request = validSignedRequest("tk-abc");
        when(memberFacade.resolveMember(any())).thenThrow(new IllegalStateException("boom"));

        RtmartMemberResponse response = controller.resolveMember(request);

        assertThat(response.getResult()).isEqualTo("fail");
        assertThat(response.getCode()).isEqualTo("E5000");
        assertThat(response.getMsg()).isEqualTo("系统错误");
    }

    private RtmartMemberRequest validSignedRequest(String token) {
        RtmartMemberRequest request = new RtmartMemberRequest();
        request.setAppKey(APP_KEY);
        request.setVersion("1");
        long timestamp = System.currentTimeMillis() / 1000;
        request.setTimestamp(timestamp);
        request.setNonceStr("nonce-test-value");
        request.setToken(token);
        request.setSign(sign(APP_KEY, "1", timestamp, "nonce-test-value", token));
        return request;
    }

    private String sign(String appKey, String version, long timestamp, String nonceStr, String token) {
        try {
            Map<String, String> fields = new TreeMap<>();
            fields.put("app_key", appKey);
            fields.put("version", version);
            fields.put("timestamp", String.valueOf(timestamp));
            fields.put("nonce_str", nonceStr);
            fields.put("token", token);

            StringBuilder canonical = new StringBuilder();
            fields.forEach((key, value) -> canonical.append(key).append(value));

            Signature signer = Signature.getInstance("SHA1withRSA");
            signer.initSign(keyPair.getPrivate());
            signer.update(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private InstoreMemberRespDTO member(String userCode, String mobile, String userName, String companyCode) {
        InstoreMemberRespDTO member = new InstoreMemberRespDTO();
        member.setUserCode(userCode);
        member.setMobile(mobile);
        member.setUserName(userName);
        member.setCompanyCode(companyCode);
        return member;
    }
}
