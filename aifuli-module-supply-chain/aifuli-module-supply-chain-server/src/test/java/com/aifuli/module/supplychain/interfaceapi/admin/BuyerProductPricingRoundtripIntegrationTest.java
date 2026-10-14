package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerSkuPricingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService.ProjectOverrides;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.HideRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * 真实 MySQL/Liquibase 保存—查询—再保存门禁：五组原始规则、当前指针与不可变历史必须无损。
 * 设计见 docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#63-分销商品原始配置回显与无损编辑2026-10-14-修复。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BuyerProductPricingRoundtripIntegrationTest extends BuyerProductPricingTestSupport {
    @Test
    void saveQueryAndResavePreserveEveryOriginalGroupAndHistory() throws Exception {
        for (Integer legacyThreshold : java.util.Arrays.asList(null, 7)) {
            Fixture fixture = fixture(legacyThreshold);
            ProjectOverrides original = overrides();
            pricing.saveBuyer(fixture.buyer().getBuyerCode(), List.of(fixture.product().getBuyerProductCode()), original, 7L);
            var firstIdentity = identity(fixture);
            var versions = context.getBean(BuyerSkuPricingVersionMapper.class);
            var firstVersion = versions.selectById(firstIdentity.getCurrentVersionId());
            var beforeRead = snapshot();

            var row = mapper.valueToTree(read(fixture));
            assertThat(row.path("currentPricingVersionId").asLong()).isEqualTo(firstVersion.getId());
            assertThat(row.path("pricingStatus").asText()).isEqualTo("DEPENDENCY_MISSING");
            assertThat(row.path("buyerPricingConfig")).isEqualTo(mapper.valueToTree(original));
            assertThat(row.path("buyerPricingConfig").path("hideRule").path("distributionThresholdPercent").asInt()).isEqualTo(2);
            assertThat(row.path("buyerPricingConfig").path("hideRule").path("salesThresholdPercent").asInt()).isEqualTo(5);
            assertThat(snapshot()).usingRecursiveComparison().isEqualTo(beforeRead);

            ProjectOverrides reloaded = mapper.treeToValue(row.path("buyerPricingConfig"), ProjectOverrides.class);
            pricing.saveBuyer(fixture.buyer().getBuyerCode(), List.of(fixture.product().getBuyerProductCode()), reloaded, 7L);
            var secondIdentity = identity(fixture);
            var secondVersion = versions.selectById(secondIdentity.getCurrentVersionId());
            assertThat(secondIdentity.getId()).isEqualTo(firstIdentity.getId());
            assertThat(secondVersion.getId()).isNotEqualTo(firstVersion.getId());
            assertThat(secondVersion.getVersionNo()).isEqualTo(firstVersion.getVersionNo() + 1);
            assertThat(mapper.readTree(secondVersion.getOverrideJson())).isEqualTo(mapper.readTree(firstVersion.getOverrideJson()));
            assertThat(secondVersion.getDistributionPriceCents()).isEqualTo(firstVersion.getDistributionPriceCents());
            assertThat(secondVersion.getSalesPriceCents()).isEqualTo(firstVersion.getSalesPriceCents());
            assertThat(versions.selectById(firstVersion.getId())).usingRecursiveComparison().isEqualTo(firstVersion);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_buyer_sku_pricing_version WHERE pricing_id=?",
                    Long.class, firstIdentity.getId())).isEqualTo(2L);
        }
    }

    @Test
    void unpricedReadDoesNotCreateIdentityOrPrefillLegacyThreshold() {
        Fixture fixture = fixture(7);
        var beforeRead = snapshot();
        var row = mapper.valueToTree(read(fixture));
        assertThat(row.path("currentPricingVersionId").isNull()).isTrue();
        assertThat(row.path("pricingStatus").asText()).isEqualTo("UNPRICED");
        assertThat(row.path("buyerPricingConfig").isNull()).isTrue();
        assertThat(identity(fixture)).isNull();
        assertThat(snapshot()).usingRecursiveComparison().isEqualTo(beforeRead);
    }

    @Test
    void inheritedFlagsRawRulesAndDoubleNullHideOverrideSurviveSaveQueryResave() throws Exception {
        Fixture fixture = fixture(7);
        var raw = overrides();
        for (ProjectOverrides original : List.of(
                new ProjectOverrides(true, raw.distributionRule(), true, raw.salesRule(),
                        true, raw.serviceFeeRule(), true, raw.hideRule(), true, raw.taxMetadata()),
                new ProjectOverrides(true, null, true, null, true, null,
                        false, new HideRule(null, null), true, null))) {
            pricing.saveBuyer(fixture.buyer().getBuyerCode(), List.of(fixture.product().getBuyerProductCode()), original, 7L);
            var expected = mapper.valueToTree(original);
            var config = mapper.valueToTree(read(fixture)).path("buyerPricingConfig");
            assertThat(config).isEqualTo(expected);
            pricing.saveBuyer(fixture.buyer().getBuyerCode(), List.of(fixture.product().getBuyerProductCode()),
                    mapper.treeToValue(config, ProjectOverrides.class), 7L);
            assertThat(mapper.valueToTree(read(fixture)).path("buyerPricingConfig")).isEqualTo(config);
        }
    }

    @Test
    void secondVersionInsertFailureRollsBackFirstVersionAndAllPointers() {
        Fixture first = fixture(7);
        Fixture other = fixture(null);
        var secondProduct = newProduct(first.buyer().getId(), other.sku().getId(), "ROLLBACK_SECOND",
                null, LocalDateTime.now());
        var products = List.of(first.product().getBuyerProductCode(), secondProduct.getBuyerProductCode());
        pricing.saveBuyer(first.buyer().getBuyerCode(), products, ProjectOverrides.allInherited(), 7L);
        Long secondPricingId = jdbc.queryForObject("SELECT id FROM sc_buyer_sku_pricing WHERE buyer_product_id=?",
                Long.class, secondProduct.getId());
        var before = snapshot();
        jdbc.execute("CREATE TRIGGER reject_second_roundtrip_version BEFORE INSERT ON sc_buyer_sku_pricing_version "
                + "FOR EACH ROW BEGIN IF NEW.pricing_id = " + secondPricingId
                + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='roundtrip injected second write failure'; END IF; END");
        try {
            assertThatThrownBy(() -> pricing.saveBuyer(first.buyer().getBuyerCode(), products, overrides(), 7L))
                    .isInstanceOf(RuntimeException.class).hasStackTraceContaining("roundtrip injected second write failure");
            assertThat(snapshot()).usingRecursiveComparison().isEqualTo(before);
        } finally {
            jdbc.execute("DROP TRIGGER reject_second_roundtrip_version");
        }
    }
}
