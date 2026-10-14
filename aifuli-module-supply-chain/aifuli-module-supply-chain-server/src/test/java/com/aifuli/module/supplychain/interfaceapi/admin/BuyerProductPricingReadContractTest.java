package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.enums.UserTypeEnum;
import com.aifuli.framework.jackson.config.AifuliJacksonAutoConfiguration;
import com.aifuli.framework.security.core.LoginUser;
import com.aifuli.framework.security.core.service.SecurityFrameworkService;
import com.aifuli.framework.security.core.util.SecurityFrameworkUtils;
import com.aifuli.framework.web.core.handler.GlobalExceptionHandler;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService.ProjectOverrides;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 真实 HTTP JSON、方法授权与买家作用域门禁；Controller、查询与数据库均使用生产实现。
 * 设计见 docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#631-当前通用定价编辑快照2026-10-14-修复。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BuyerProductPricingReadContractTest extends BuyerProductPricingTestSupport {
    private MockMvc mvc;

    @BeforeEach
    void httpContext() {
        var user = new LoginUser(); user.setId(7L); user.setUserType(UserTypeEnum.ADMIN.getValue());
        SecurityFrameworkUtils.setLoginUser(user, new MockHttpServletRequest());
        when(context.getBean("ss", SecurityFrameworkService.class).hasAnyPermissions(any(String[].class))).thenReturn(true);
        // 复用真实应用 wire 定制，避免默认 MVC mapper 漏测超出 JS 安全范围的 Long 字符串契约。
        var appJackson = new AifuliJacksonAutoConfiguration();
        var builder = Jackson2ObjectMapperBuilder.json();
        appJackson.ldtEpochMillisCustomizer().customize(builder);
        var httpMapper = builder.build();
        httpMapper.registerModule(appJackson.timestampSupportModuleBean());
        mvc = MockMvcBuilders.standaloneSetup(context.getBean(BuyerAdminController.class))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(httpMapper))
                .setControllerAdvice(new GlobalExceptionHandler("supply-chain-server", null)).build();
    }

    @AfterEach
    void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void productsHttpSerializesAllFiveOriginalGroupsAndCurrentPointer() throws Exception {
        Fixture fixture = fixture(7);
        pricing.saveBuyer(fixture.buyer().getBuyerCode(), List.of(fixture.product().getBuyerProductCode()), overrides(), 7L);
        String response = mvc.perform(get(path(fixture))).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list[0].pricingStatus").value("DEPENDENCY_MISSING"))
                .andExpect(jsonPath("$.data.list[0].buyerPricingConfig.hideRule.distributionThresholdPercent").value(2))
                .andExpect(jsonPath("$.data.list[0].buyerPricingConfig.hideRule.salesThresholdPercent").value(5))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var row = mapper.readTree(response).path("data").path("list").get(0);
        assertThat(row.path("currentPricingVersionId").asText()).isEqualTo(identity(fixture).getCurrentVersionId().toString());
        var original = mapper.treeToValue(row.path("buyerPricingConfig"), ProjectOverrides.class);
        assertThat(original).usingRecursiveComparison()
                .withComparatorForType(java.math.BigDecimal::compareTo, java.math.BigDecimal.class)
                .isEqualTo(overrides());
    }

    @Test
    void productionHttpPreservesLargeVersionIdAsStringAndDecimalThresholdAsNumber() throws Exception {
        Fixture fixture = fixture(null);
        var original = overrides();
        var decimalHide = new ProjectOverrides(original.distributionInherited(), original.distributionRule(),
                original.salesInherited(), original.salesRule(), original.serviceFeeInherited(), original.serviceFeeRule(),
                false, new com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.HideRule(
                        new java.math.BigDecimal("2.25"), new java.math.BigDecimal("5")),
                original.taxInherited(), original.taxMetadata());
        pricing.saveBuyer(fixture.buyer().getBuyerCode(), List.of(fixture.product().getBuyerProductCode()), decimalHide, 7L);
        var identity = identity(fixture);
        long largeVersionId = 9007199254740993L;
        // 仅在隔离数据库准备大编号边界夹具；生产保存仍使用不可变版本追加。
        jdbc.update("UPDATE sc_buyer_sku_pricing_version SET id=? WHERE id=?", largeVersionId, identity.getCurrentVersionId());
        jdbc.update("UPDATE sc_buyer_sku_pricing SET current_version_id=? WHERE id=?", largeVersionId, identity.getId());

        String response = mvc.perform(get(path(fixture))).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var row = mapper.readTree(response).path("data").path("list").get(0);
        assertThat(row.path("currentPricingVersionId").isTextual()).isTrue();
        assertThat(row.path("currentPricingVersionId").asText()).isEqualTo("9007199254740993");
        var threshold = row.path("buyerPricingConfig").path("hideRule").path("distributionThresholdPercent");
        assertThat(threshold.isNumber()).isTrue();
        assertThat(threshold.decimalValue()).isEqualByComparingTo("2.25");
        var reloaded = mapper.treeToValue(row.path("buyerPricingConfig"), ProjectOverrides.class);
        assertThat(reloaded).usingRecursiveComparison()
                .withComparatorForType(java.math.BigDecimal::compareTo, java.math.BigDecimal.class)
                .isEqualTo(decimalHide);
    }

    @Test
    void productsHttpIncludesExplicitUnpricedNullFields() throws Exception {
        Fixture fixture = fixture(7);
        String response = mvc.perform(get(path(fixture))).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
        var row = mapper.readTree(response).path("data").path("list").get(0);
        assertThat(row.has("currentPricingVersionId")).isTrue();
        assertThat(row.path("currentPricingVersionId").isNull()).isTrue();
        assertThat(row.path("pricingStatus").asText()).isEqualTo("UNPRICED");
        assertThat(row.has("buyerPricingConfig")).isTrue();
        assertThat(row.path("buyerPricingConfig").isNull()).isTrue();
    }

    @Test
    void deniedBuyerQueryPermissionReturnsForbidden() throws Exception {
        Fixture fixture = fixture(null);
        var before = snapshot();
        when(context.getBean("ss", SecurityFrameworkService.class).hasAnyPermissions(any(String[].class))).thenReturn(false);
        mvc.perform(get(path(fixture))).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(403));
        assertThat(snapshot()).usingRecursiveComparison().isEqualTo(before);
    }

    @Test
    void buyerScopeIsAppliedBeforePageAndForeignProjectIsRejected() throws Exception {
        Fixture first = fixture(null), second = fixture(7);
        pricing.saveBuyer(first.buyer().getBuyerCode(), List.of(first.product().getBuyerProductCode()), overrides(), 7L);
        pricing.saveBuyer(second.buyer().getBuyerCode(), List.of(second.product().getBuyerProductCode()), ProjectOverrides.allInherited(), 7L);
        mvc.perform(get(path(first))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].buyerProductCode").value(first.product().getBuyerProductCode()));
        var now = LocalDateTime.now();
        var foreign = BuyerProjectDO.builder().projectCode("FOREIGN_PRJ_" + sequence).projectName("其他买家项目")
                .buyerId(second.buyer().getId()).catalogMode("MANAGED").settlementPartyType("DISTRIBUTOR")
                .status((short) 10).version(0L).createdBy(1L).updatedBy(1L).createdAt(now).updatedAt(now).build();
        context.getBean(BuyerProjectMapper.class).insert(foreign);
        mvc.perform(get(path(first)).param("projectCode", foreign.getProjectCode())).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_NOT_FOUND.getCode()));
    }

    @Test
    void corruptCurrentConfigFailsHttpReadWithoutPresentingUnpricedDefaults() throws Exception {
        Fixture fixture = fixture(null);
        pricing.saveBuyer(fixture.buyer().getBuyerCode(), List.of(fixture.product().getBuyerProductCode()), overrides(), 7L);
        jdbc.update("UPDATE sc_buyer_sku_pricing_version SET override_json='{}' WHERE id=?", identity(fixture).getCurrentVersionId());
        mvc.perform(get(path(fixture))).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private String path(Fixture fixture) { return "/supply-chain/buyers/" + fixture.buyer().getBuyerCode() + "/products"; }
}
