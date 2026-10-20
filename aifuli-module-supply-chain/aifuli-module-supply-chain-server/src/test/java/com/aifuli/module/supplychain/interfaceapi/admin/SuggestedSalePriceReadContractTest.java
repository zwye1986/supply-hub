package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.module.supplychain.tradecore.catalog.application.*;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.SuggestedSalePriceResolver.Resolution;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.SuggestedPriceStatus;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierCooperativeProductService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.converter.ModelConverters;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** 动态现价、历史参考和原因不可混用；设计见2026-10-19建议价供应价设计§7。 */
class SuggestedSalePriceReadContractTest {
    private static final List<Class<?>> PROJECTIONS = List.of(
            ProductCatalogQueryService.SkuDetail.class, ProductCatalogQueryService.ProductManagementSkuItem.class,
            PlatformSkuQueryService.SkuListItem.class, BuyerProductQueryService.BuyerProductListItem.class,
            ProjectListingQueryService.ProjectListingListItem.class, SupplierCooperativeProductService.OfferListItem.class);

    @ParameterizedTest
    @EnumSource(SuggestedPriceStatus.class)
    void allReadModelsExposeCurrentStatusAndSeparateHistory(SuggestedPriceStatus status) throws Exception {
        LocalDateTime lastAt = LocalDateTime.of(2026, 10, 18, 10, 30);
        Long amount = status == SuggestedPriceStatus.READY ? 0L : null;
        String reason = status == SuggestedPriceStatus.READY ? null : "MISSING_OR_FAILED";
        var resolution = new Resolution(amount, status, reason, null, 12345L, lastAt, null);
        var mapper = new ObjectMapper().findAndRegisterModules();
        mapper.registerModule(new com.aifuli.framework.jackson.config.AifuliJacksonAutoConfiguration().timestampSupportModuleBean());
        for (Class<?> type : PROJECTIONS) {
            var components = type.getRecordComponents();
            Object[] args = Arrays.stream(components).map(component -> {
                if (component.getName().equals("suggestedPriceResolution")) return resolution;
                if (List.of("suggestedSalePrice", "suggestedSalePriceCents", "marketPriceCents")
                        .contains(component.getName())) return amount;
                return component.getType() == boolean.class ? false : null;
            }).toArray();
            var constructor = type.getDeclaredConstructor(Arrays.stream(components)
                    .map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new));
            var json = mapper.valueToTree(constructor.newInstance(args));
            assertThat(json.path("suggestedSalePriceStatus").asText()).as(type.getSimpleName()).isEqualTo(status.name());
            assertThat(json.get("suggestedSalePriceUnavailableReason").isNull() ? null
                    : json.get("suggestedSalePriceUnavailableReason").asText()).isEqualTo(reason);
            assertThat(json.get("lastValidSuggestedSalePrice").asLong()).isEqualTo(12345L);
            assertThat(json.get("lastValidSuggestedSalePriceCalculatedAt").isIntegralNumber()).isTrue();
            assertThat(json.get("lastValidSuggestedSalePriceCalculatedAt").asLong())
                    .isEqualTo(lastAt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
            assertThat(json.has("suggestedPriceResolution")).isFalse();
            var schema = ModelConverters.getInstance().read(type).get(type.getSimpleName());
            assertThat(schema.getProperties()).containsKeys("suggestedSalePriceStatus",
                    "suggestedSalePriceUnavailableReason", "lastValidSuggestedSalePrice", "lastValidSuggestedSalePriceCalculatedAt");
            assertThat(schema.getProperties()).doesNotContainKey("suggestedPriceResolution");
            var timeSchema = (io.swagger.v3.oas.models.media.Schema<?>)schema.getProperties().get("lastValidSuggestedSalePriceCalculatedAt");
            assertThat(timeSchema.getType()).isEqualTo("integer");
            assertThat(timeSchema.getFormat()).isEqualTo("int64");
        }
    }
}
