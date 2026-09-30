package com.aifuli.module.member.api.sso;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.member.api.sso.dto.MemberSsoTokenReqDTO;
import com.aifuli.module.member.api.sso.dto.MemberSsoTokenRespDTO;
import com.aifuli.module.member.enums.ApiConstants;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会员 SSO 令牌 RPC 合同测试。
 *
 * <p>详细设计：docs/superpowers/specs/2026-08-28-buyer-cashier-h5-url-adapter-design.md#31-member-api</p>
 */
class MemberSsoTokenApiContractTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void declaresDedicatedFeignEndpointAndResponseType() throws Exception {
        FeignClient feignClient = MemberSsoTokenApi.class.getAnnotation(FeignClient.class);
        assertThat(feignClient).isNotNull();
        assertThat(feignClient.name()).isEqualTo(ApiConstants.NAME);
        assertThat(MemberSsoTokenApi.PREFIX).isEqualTo(ApiConstants.PREFIX + "/sso");

        Method method = MemberSsoTokenApi.class.getMethod("createToken", MemberSsoTokenReqDTO.class);
        assertThat(method.getAnnotation(PostMapping.class).value())
                .containsExactly(MemberSsoTokenApi.PREFIX + "/token");
        assertThat(method.getParameters()[0].isAnnotationPresent(Valid.class)).isTrue();
        assertThat(method.getParameters()[0].isAnnotationPresent(RequestBody.class)).isTrue();
        ParameterizedType returnType = (ParameterizedType) method.getGenericReturnType();
        assertThat(returnType.getRawType()).isEqualTo(CommonResult.class);
        assertThat(returnType.getActualTypeArguments()).containsExactly(MemberSsoTokenRespDTO.class);
    }

    @Test
    void requestUsesThreeRequiredBoundedStringIdentityFields() {
        MemberSsoTokenReqDTO request = request("AIFULI_MALL", "100", "1024");
        assertThat(validator.validate(request)).isEmpty();

        assertThat(validator.validate(request(" ", "100", "1024")))
                .extracting(violation -> violation.getPropertyPath().toString()).contains("sourceSystem");
        assertThat(validator.validate(request("AIFULI_MALL", " ", "1024")))
                .extracting(violation -> violation.getPropertyPath().toString()).contains("externalTenantId");
        assertThat(validator.validate(request("AIFULI_MALL", "100", " ")))
                .extracting(violation -> violation.getPropertyPath().toString()).contains("buyerUserId");
        assertThat(validator.validate(request("s".repeat(33), "t".repeat(129), "b".repeat(129))))
                .hasSize(3);
    }

    @Test
    void dtoDiagnosticStringsRedactIdentityAndToken() {
        MemberSsoTokenReqDTO request = request("AIFULI_MALL", "tenant-secret", "buyer-secret");
        assertThat(request.toString())
                .contains("AIFULI_MALL", "externalTenantId=<redacted>", "buyerUserId=<redacted>")
                .doesNotContain("tenant-secret", "buyer-secret");

        MemberSsoTokenRespDTO response = new MemberSsoTokenRespDTO();
        response.setAccessToken("token-secret");
        assertThat(response.toString()).contains("accessToken=<redacted>").doesNotContain("token-secret");
    }

    private static MemberSsoTokenReqDTO request(String sourceSystem, String externalTenantId,
                                                 String buyerUserId) {
        MemberSsoTokenReqDTO request = new MemberSsoTokenReqDTO();
        request.setSourceSystem(sourceSystem);
        request.setExternalTenantId(externalTenantId);
        request.setBuyerUserId(buyerUserId);
        return request;
    }
}
