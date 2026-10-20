package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCategoryCoverageService.CoverageResolver;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductCategoryDO;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSupplyScopeDO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三入口共用范围判定：不查库，归档不断链、枚举兼容、最具体唯一性均由真实解析器验证。
 * 设计见 docs/superpowers/specs/2026-10-20-supplier-supply-scope-matching-design.md#2-唯一匹配算法。
 */
class SupplierSupplyScopeMatcherTest {
    private final CoverageResolver coverage = new CoverageResolver(List.of(
            category(1L, "ROOT", null, (short) 1, CatalogEntityStatus.ACTIVE.getCode()),
            category(2L, "MIDDLE", 1L, (short) 2, CatalogEntityStatus.ARCHIVED.getCode()),
            category(3L, "LEAF", 2L, (short) 3, CatalogEntityStatus.ACTIVE.getCode()),
            category(4L, "SIBLING", 2L, (short) 3, CatalogEntityStatus.ACTIVE.getCode())));

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"PHYSICAL", "EQUITY"})
    void sellableCategoryIsNotAMatchingInput(String sellableCategory) {
        var spu = spu("TYPE_A", "LEAF");
        spu.setSellableCategory(sellableCategory);
        var scope = scope(10L, "TYPE_A", "ROOT");
        scope.setSellableCategory("MOVIE");
        assertThat(SupplierSupplyScopeMatcher.match(List.of(scope), spu, coverage).scope()).isSameAs(scope);
    }

    @ParameterizedTest
    @CsvSource({"ROOT,LEAF,true", "LEAF,LEAF,true", "LEAF,ROOT,false", "SIBLING,LEAF,false",
            "VIRTUAL_CARD,VIRTUAL_CARD,true", "VIRTUAL_CARD,LEAF,false", "ROOT,VIRTUAL_CARD,false",
            "UNKNOWN,LEAF,false"})
    void classificationCoverageAndEnumCompatibility(String configured, String actual, boolean matches) {
        assertThat(SupplierSupplyScopeMatcher.match(List.of(scope(10L, "TYPE_A", configured)),
                spu("TYPE_A", actual), coverage).matched()).isEqualTo(matches);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "TYPE_B"})
    void missingOrDifferentTypeDoesNotMatch(String type) {
        assertThat(SupplierSupplyScopeMatcher.match(List.of(scope(10L, "TYPE_A", "ROOT")),
                spu(type, "LEAF"), coverage).matched()).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void missingClassificationNeverMatches(String classification) {
        assertThat(SupplierSupplyScopeMatcher.match(List.of(scope(10L, "TYPE_A", classification)),
                spu("TYPE_A", classification), coverage).matched()).isFalse();
    }

    @Test
    void typeAndClassificationMustComeFromTheSameScope() {
        var scopes = List.of(scope(10L, "TYPE_A", "ROOT"), scope(11L, "TYPE_B", "SIBLING"));
        assertThat(SupplierSupplyScopeMatcher.match(scopes, spu("TYPE_B", "LEAF"), coverage).matched()).isFalse();
    }

    @Test
    void uniqueDeepestWinsEvenWhenShallowerScopesConflictAndOrderChanges() {
        var deepest = scope(12L, "TYPE_A", "LEAF");
        var scopes = new ArrayList<>(List.of(scope(10L, "TYPE_A", "ROOT"), scope(11L, "TYPE_A", "ROOT"), deepest));
        for (int index = 0; index < scopes.size(); index++) {
            Collections.rotate(scopes, 1);
            var result = SupplierSupplyScopeMatcher.match(scopes, spu("TYPE_A", "LEAF"), coverage);
            assertThat(result.scope()).isSameAs(deepest);
            assertThat(result.conflict()).isFalse();
        }
    }

    @Test
    void deepestTieNeverFallsBackToShallowerScopeOrFirstRow() {
        var scopes = new ArrayList<>(List.of(scope(10L, "TYPE_A", "ROOT"), scope(11L, "TYPE_A", "LEAF"),
                scope(12L, "TYPE_A", "LEAF")));
        for (int index = 0; index < scopes.size(); index++) {
            Collections.rotate(scopes, 1);
            var result = SupplierSupplyScopeMatcher.match(scopes, spu("TYPE_A", "LEAF"), coverage);
            assertThat(result.scope()).isNull();
            assertThat(result.conflict()).isTrue();
            assertThat(result.matched()).isFalse();
        }
    }

    @Test
    void noConfigurationIsOutOfScopeRatherThanConflict() {
        var result = SupplierSupplyScopeMatcher.match(List.of(), spu("TYPE_A", "LEAF"), coverage);
        assertThat(result.matched()).isFalse();
        assertThat(result.conflict()).isFalse();
    }

    private static PlatformSpuDO spu(String type, String classification) {
        return PlatformSpuDO.builder().productType(type).productClassification(classification).build();
    }

    private static SupplierSupplyScopeDO scope(Long id, String type, String classification) {
        return SupplierSupplyScopeDO.builder().id(id).productType(type).productClassification(classification).build();
    }

    private static ProductCategoryDO category(Long id, String code, Long parent, short level, short status) {
        return ProductCategoryDO.builder().id(id).categoryCode(code).parentId(parent).categoryLevel(level).status(status).build();
    }
}
