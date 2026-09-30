package com.aifuli.module.member.api.profile;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.member.api.profile.dto.MemberProfileResolveReqDTO;
import com.aifuli.module.member.api.profile.dto.MemberProfileResolveRespDTO;
import com.aifuli.module.member.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会员三元身份解析 RPC 合同测试。
 *
 * <p>详细设计：docs/superpowers/specs/2026-08-26-saas-member-profile-resolver-design.md#31-member-api</p>
 */
class MemberProfileResolveApiContractTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void declaresFeignResolveEndpointContract() throws Exception {
        FeignClient feignClient = MemberProfileResolveApi.class.getAnnotation(FeignClient.class);
        assertNotNull(feignClient);
        assertEquals(ApiConstants.NAME, feignClient.name());
        assertEquals(ApiConstants.PREFIX + "/profile", MemberProfileResolveApi.PREFIX);
        Tag tag = MemberProfileResolveApi.class.getAnnotation(Tag.class);
        assertNotNull(tag);
        assertEquals("RPC 服务 - 会员资料解析", tag.name());

        Method method = MemberProfileResolveApi.class.getMethod("resolve", MemberProfileResolveReqDTO.class);
        assertEquals(CommonResult.class, method.getReturnType());
        ParameterizedType returnType = assertInstanceOf(ParameterizedType.class, method.getGenericReturnType());
        assertEquals(CommonResult.class, returnType.getRawType());
        assertEquals(1, returnType.getActualTypeArguments().length);
        assertEquals(MemberProfileResolveRespDTO.class, returnType.getActualTypeArguments()[0]);
        assertEquals(MemberProfileResolveApi.PREFIX + "/resolve", method.getAnnotation(PostMapping.class).value()[0]);
        Operation operation = method.getAnnotation(Operation.class);
        assertNotNull(operation);
        assertEquals("按外部三元身份解析会员资料", operation.summary());
        assertTrue(method.getParameters()[0].isAnnotationPresent(Valid.class));
        assertTrue(method.getParameters()[0].isAnnotationPresent(RequestBody.class));
    }

    @Test
    void declaresThreeStringIdentityFields() throws Exception {
        assertEquals(String.class, MemberProfileResolveReqDTO.class.getDeclaredField("sourceSystem").getType());
        assertEquals(String.class, MemberProfileResolveReqDTO.class.getDeclaredField("externalTenantId").getType());
        assertEquals(String.class, MemberProfileResolveReqDTO.class.getDeclaredField("buyerUserId").getType());
    }

    @Test
    void acceptsOrdinaryAndExactBoundaryIdentityValues() {
        assertValid("source", "tenant", "buyer");
        assertValid(repeat('s', 32), repeat('t', 128), repeat('b', 128));
    }

    @Test
    void rejectsBlankSourceSystemWithNotBlankConstraint() {
        assertSingleViolation(" ", "tenant", "buyer", "sourceSystem", NotBlank.class, "来源系统不能为空");
    }

    @Test
    void rejectsBlankExternalTenantIdWithNotBlankConstraint() {
        assertSingleViolation("source", "", "buyer", "externalTenantId", NotBlank.class, "外部租户标识不能为空");
    }

    @Test
    void rejectsBlankBuyerUserIdWithNotBlankConstraint() {
        assertSingleViolation("source", "tenant", null, "buyerUserId", NotBlank.class, "采购商用户标识不能为空");
    }

    @Test
    void rejectsOverlongSourceSystemWithSizeConstraint() {
        assertSingleViolation(repeat('s', 33), "tenant", "buyer", "sourceSystem", Size.class, "来源系统长度不能超过32");
    }

    @Test
    void rejectsOverlongExternalTenantIdWithSizeConstraint() {
        assertSingleViolation("source", repeat('t', 129), "buyer", "externalTenantId", Size.class, "外部租户标识长度不能超过128");
    }

    @Test
    void rejectsOverlongBuyerUserIdWithSizeConstraint() {
        assertSingleViolation("source", "tenant", repeat('b', 129), "buyerUserId", Size.class, "采购商用户标识长度不能超过128");
    }

    @Test
    void exposesOnlyMobileAndUserNameInResponse() {
        Set<String> fields = java.util.Arrays.stream(MemberProfileResolveRespDTO.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic() && !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("mobile", "userName"), fields);
    }

    @Test
    void declaresOnlyAifuliMallAsSupportedSourceSystem() {
        Set<String> fields = java.util.Arrays.stream(MemberProfileSourceSystemConstants.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .map(Field::getName)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("AIFULI_MALL"), fields);
        assertEquals("AIFULI_MALL", MemberProfileSourceSystemConstants.AIFULI_MALL);
    }

    @Test
    void redactsSensitiveValuesInDtoToString() {
        MemberProfileResolveReqDTO request = new MemberProfileResolveReqDTO();
        request.setSourceSystem("source-sentinel");
        request.setExternalTenantId("tenant-secret-sentinel");
        request.setBuyerUserId("buyer-secret-sentinel");
        assertTrue(request.toString().contains("source-sentinel"));
        assertTrue(request.toString().contains("externalTenantId=<redacted>"));
        assertTrue(request.toString().contains("buyerUserId=<redacted>"));
        assertFalse(request.toString().contains("tenant-secret-sentinel"));
        assertFalse(request.toString().contains("buyer-secret-sentinel"));

        MemberProfileResolveRespDTO response = new MemberProfileResolveRespDTO();
        response.setMobile("mobile-secret-sentinel");
        response.setUserName("user-name-secret-sentinel");
        assertTrue(response.toString().contains("mobile=<redacted>"));
        assertTrue(response.toString().contains("userName=<redacted>"));
        assertFalse(response.toString().contains("mobile-secret-sentinel"));
        assertFalse(response.toString().contains("user-name-secret-sentinel"));
    }

    private void assertValid(String sourceSystem, String externalTenantId, String buyerUserId) {
        MemberProfileResolveReqDTO request = new MemberProfileResolveReqDTO();
        request.setSourceSystem(sourceSystem);
        request.setExternalTenantId(externalTenantId);
        request.setBuyerUserId(buyerUserId);
        assertTrue(validator.validate(request).isEmpty());
    }

    private void assertSingleViolation(String sourceSystem, String externalTenantId, String buyerUserId,
                                       String propertyPath, Class<?> constraintType, String message) {
        MemberProfileResolveReqDTO request = new MemberProfileResolveReqDTO();
        request.setSourceSystem(sourceSystem);
        request.setExternalTenantId(externalTenantId);
        request.setBuyerUserId(buyerUserId);
        Set<jakarta.validation.ConstraintViolation<MemberProfileResolveReqDTO>> violations = validator.validate(request);
        assertEquals(1, violations.size());
        jakarta.validation.ConstraintViolation<MemberProfileResolveReqDTO> violation = violations.iterator().next();
        assertEquals(propertyPath, violation.getPropertyPath().toString());
        assertEquals(constraintType, violation.getConstraintDescriptor().getAnnotation().annotationType());
        assertEquals(message, violation.getMessage());
    }

    private String repeat(char value, int count) {
        return String.valueOf(value).repeat(count);
    }

}
