package com.aifuli.module.supplychain.api.catalog;

import com.aifuli.module.supplychain.api.catalog.dto.BuyerCatalogChangeRespDTO;
import com.aifuli.module.supplychain.api.catalog.dto.BuyerCatalogItemRespDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 目录读取契约勘误：字段描述须对应实际商业版本、状态枚举及 UTC 输出，不能改变原字段边界。
 *
 * <p>设计见
 * {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#561-目录读取字段与注释契约}。
 * 这里检查真实 DTO 的运行时注解，不替换目录查询或价格来源。</p>
 */
class BuyerCatalogContractTest {

    @ParameterizedTest(name = "{0}.{1} 与既有目录读取语义一致")
    @MethodSource("projectedFieldDescriptions")
    void describesExistingProjectionWithoutChangingFieldType(Class<?> dtoType, String fieldName,
                                                              String description, Class<?> fieldType) throws Exception {
        Field field = dtoType.getDeclaredField(fieldName);
        assertThat(field.getType()).isEqualTo(fieldType);
        Schema schema = field.getAnnotation(Schema.class);
        assertThat(schema).isNotNull();
        assertThat(schema.description()).isEqualTo(description);
        assertThat(schema.requiredMode()).isEqualTo(Schema.RequiredMode.REQUIRED);
    }

    @Test
    void keepsOriginalCatalogItemFieldBoundary() {
        assertThat(Arrays.stream(BuyerCatalogItemRespDTO.class.getDeclaredFields()).map(Field::getName))
                .containsExactlyInAnyOrder("buyerSkuCode", "listingVersion", "platformSkuCode", "platformSpuCode",
                        "displayName", "brandCode", "brandName", "categoryCode", "categoryName", "fulfillmentType",
                        "buyerSettlementUnitPrice", "currency", "listingStatus", "availabilityStatus",
                        "availabilityVersion", "updatedAt");
    }

    @Test
    void keepsOriginalCatalogChangeFieldBoundary() {
        assertThat(Arrays.stream(BuyerCatalogChangeRespDTO.class.getDeclaredFields()).map(Field::getName))
                .containsExactlyInAnyOrder("cursor", "changeType", "buyerSkuCode", "item", "occurredAt");
    }

    private static Stream<Arguments> projectedFieldDescriptions() {
        return Stream.of(
                Arguments.of(BuyerCatalogItemRespDTO.class, "listingVersion", "采购商商品业务版本号，从1递增", Long.class),
                Arguments.of(BuyerCatalogItemRespDTO.class, "displayName", "向采购商商城展示的商品名称", String.class),
                Arguments.of(BuyerCatalogItemRespDTO.class, "buyerSettlementUnitPrice", "采购商向中台结算的单位价格，单位为分", Long.class),
                Arguments.of(BuyerCatalogItemRespDTO.class, "currency", "结算币种，一期固定CNY", String.class),
                Arguments.of(BuyerCatalogItemRespDTO.class, "listingStatus",
                        "采购商商品生命周期：DRAFT草稿，PUBLISHED已发布，SUSPENDED暂停，RETIRED已退役", String.class),
                Arguments.of(BuyerCatalogItemRespDTO.class, "availabilityStatus",
                        "供给可用性：AVAILABLE可用，UNAVAILABLE不可用，UNKNOWN未知", String.class),
                Arguments.of(BuyerCatalogItemRespDTO.class, "updatedAt", "采购商商品最后修改时间，UTC", OffsetDateTime.class),
                Arguments.of(BuyerCatalogChangeRespDTO.class, "occurredAt", "变更发生时间，UTC", OffsetDateTime.class)
        );
    }
}
