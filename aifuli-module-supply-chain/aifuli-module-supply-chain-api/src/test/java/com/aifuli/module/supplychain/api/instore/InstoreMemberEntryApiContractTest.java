package com.aifuli.module.supplychain.api.instore;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.instore.dto.HomeEntryReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.InstoreEntryUrlRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.InstoreMemberRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.OrderDetailEntryReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.ResolveInstoreMemberReqDTO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 到店购会员入口 RPC 的字段边界与校验合同。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#41-api-层}。</p>
 */
class InstoreMemberEntryApiContractTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void keepsExactExternalDtoFieldBoundaries() {
        assertBusinessFields(HomeEntryReqDTO.class, Map.of(
                "longitude", String.class, "latitude", String.class));
        assertBusinessFields(OrderDetailEntryReqDTO.class, Map.of("orderNo", String.class));
        assertBusinessFields(ResolveInstoreMemberReqDTO.class, Map.of("token", String.class));
        assertBusinessFields(InstoreEntryUrlRespDTO.class, Map.of("entryUrl", String.class));
        assertBusinessFields(InstoreMemberRespDTO.class, Map.of(
                "userCode", String.class, "mobile", String.class, "userName", String.class, "companyCode", String.class));
    }

    @Test
    void acceptsCompleteRequestsAndAllowsNullableUserName() {
        HomeEntryReqDTO home = new HomeEntryReqDTO();
        home.setLongitude("121.4737");
        home.setLatitude("31.2304");

        OrderDetailEntryReqDTO detail = new OrderDetailEntryReqDTO();
        detail.setOrderNo("ORDER-001");

        ResolveInstoreMemberReqDTO resolve = new ResolveInstoreMemberReqDTO();
        resolve.setToken("credential-token");

        InstoreMemberRespDTO member = new InstoreMemberRespDTO();
        member.setUserCode("user-001");
        member.setMobile("13800000000");
        member.setCompanyCode("COMPANY-001");

        assertThat(validator.validate(home)).isEmpty();
        assertThat(validator.validate(detail)).isEmpty();
        assertThat(validator.validate(resolve)).isEmpty();
        assertThat(member.getUserName()).isNull();
        assertThat(validator.validate(member)).noneMatch(
                violation -> violation.getPropertyPath().toString().equals("userName"));
    }

    @Test
    void rejectsEveryRequiredBlankField() {
        HomeEntryReqDTO home = new HomeEntryReqDTO();
        home.setLongitude(" ");
        home.setLatitude(" ");
        assertViolationProperties(home, "longitude", "latitude");

        OrderDetailEntryReqDTO detail = new OrderDetailEntryReqDTO();
        detail.setOrderNo(" ");
        assertViolationProperties(detail, "orderNo");

        ResolveInstoreMemberReqDTO resolve = new ResolveInstoreMemberReqDTO();
        resolve.setToken(" ");
        assertViolationProperties(resolve, "token");
    }

    @Test
    void enforcesSpecifiedRequestFieldLengthBoundaries() {
        HomeEntryReqDTO home = new HomeEntryReqDTO();
        home.setLongitude("l".repeat(32));
        home.setLatitude("a".repeat(32));
        assertThat(validator.validate(home)).isEmpty();

        home.setLongitude("l".repeat(33));
        assertViolationProperties(home, "longitude");
        home.setLongitude("l".repeat(32));
        home.setLatitude("a".repeat(33));
        assertViolationProperties(home, "latitude");

        OrderDetailEntryReqDTO detail = new OrderDetailEntryReqDTO();
        detail.setOrderNo("o".repeat(32));
        assertThat(validator.validate(detail)).isEmpty();
        detail.setOrderNo("o".repeat(33));
        assertViolationProperties(detail, "orderNo");

        ResolveInstoreMemberReqDTO resolve = new ResolveInstoreMemberReqDTO();
        resolve.setToken("t".repeat(256));
        assertThat(validator.validate(resolve)).isEmpty();
        resolve.setToken("t".repeat(257));
        assertViolationProperties(resolve, "token");
    }

    @Test
    void publishesOnlyCommonResultRpcMethodsWithoutInternalRoutingFields() throws Exception {
        assertApiMethod(InstoreBuyerEntryApi.class, "getHomeEntry", HomeEntryReqDTO.class,
                InstoreEntryUrlRespDTO.class, "/rpc-api/supply-chain/instore/buyer-entry/home");
        assertApiMethod(InstoreBuyerEntryApi.class, "getOrderDetailEntry", OrderDetailEntryReqDTO.class,
                InstoreEntryUrlRespDTO.class, "/rpc-api/supply-chain/instore/buyer-entry/detail");
        assertApiMethod(InstoreChannelMemberApi.class, "resolveMember", ResolveInstoreMemberReqDTO.class,
                InstoreMemberRespDTO.class, "/rpc-api/supply-chain/instore/channel-member/resolve");

        Set<String> forbiddenFields = Set.of("projectId", "buyerId", "buyerUserId", "sourceSystem",
                "externalTenantId", "memberProfileVersionId", "tradeId", "saasMemberId");
        for (Class<?> dtoType : Set.of(HomeEntryReqDTO.class, OrderDetailEntryReqDTO.class,
                ResolveInstoreMemberReqDTO.class, InstoreEntryUrlRespDTO.class, InstoreMemberRespDTO.class)) {
            assertThat(businessFields(dtoType).keySet()).doesNotContainAnyElementsOf(forbiddenFields);
        }
        assertThat(businessFields(HomeEntryReqDTO.class)).doesNotContainKey("companyCode");
    }

    @Test
    void omitsCredentialsAndMemberPersonalInformationFromToString() {
        ResolveInstoreMemberReqDTO resolve = new ResolveInstoreMemberReqDTO();
        resolve.setToken("TOKEN-SENTINEL-DO-NOT-LOG");

        InstoreEntryUrlRespDTO entryUrl = new InstoreEntryUrlRespDTO();
        entryUrl.setEntryUrl("https://entry.example/path?token=URL-TOKEN-SENTINEL");

        InstoreMemberRespDTO member = new InstoreMemberRespDTO();
        member.setUserCode("USER-CODE-SENTINEL");
        member.setMobile("MOBILE-SENTINEL");
        member.setUserName("USER-NAME-SENTINEL");
        member.setCompanyCode("COMPANY-001");

        assertThat(resolve.toString()).doesNotContain("TOKEN-SENTINEL-DO-NOT-LOG");
        assertThat(entryUrl.toString()).doesNotContain("URL-TOKEN-SENTINEL");
        assertThat(member.toString())
                .contains("COMPANY-001")
                .doesNotContain("USER-CODE-SENTINEL", "MOBILE-SENTINEL", "USER-NAME-SENTINEL");
    }

    private static void assertApiMethod(Class<?> apiType, String methodName, Class<?> requestType,
                                        Class<?> responseType, String expectedPath) throws Exception {
        Method method = apiType.getMethod(methodName, requestType);
        assertThat(method.getReturnType()).isEqualTo(CommonResult.class);
        assertThat(((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments())
                .containsExactly(responseType);
        assertThat(method.getAnnotation(PostMapping.class).value()).containsExactly(expectedPath);
    }

    private static void assertBusinessFields(Class<?> type, Map<String, Class<?>> expected) {
        assertThat(businessFields(type)).containsExactlyInAnyOrderEntriesOf(expected);
    }

    private static Map<String, Class<?>> businessFields(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !field.isSynthetic() && !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .collect(Collectors.toMap(Field::getName, Field::getType));
    }

    private static void assertViolationProperties(Object request, String... expectedProperties) {
        Set<String> properties = validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
        assertThat(properties).containsExactlyInAnyOrder(expectedProperties);
    }
}
