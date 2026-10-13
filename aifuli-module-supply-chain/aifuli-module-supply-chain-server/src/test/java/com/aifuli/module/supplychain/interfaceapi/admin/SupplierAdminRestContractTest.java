package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.module.supplychain.tradecore.supply.application.SupplierCooperativeProductService.OfferListItem;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferFulfillmentScore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 供应商管理端 REST 契约：名词资源、金额分、枚举名、开票子资源无 get/save。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#36-openapiswagger}。
 */
class SupplierAdminRestContractTest {

    /** 新增销售建议价与历史L1价格同时序列化，兼容提示价可以缺省。 */
    @Test
    void offerPricesKeepDistinctJsonMeaningsAndDocumentSkuSalePrice() throws Exception {
        var sale = Arrays.stream(OfferListItem.class.getRecordComponents())
                .filter(component -> "suggestedSalePriceCents".equals(component.getName())).findFirst().orElse(null);
        assertThat(sale).as("SKU销售建议价必须成为独立JSON字段").isNotNull();
        assertThat(sale.getAccessor().getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class).description())
                .isEqualTo("建议零售价/销售建议价，单位为分");
        ObjectMapper mapper = new ObjectMapper();
        OfferListItem item = mapper.convertValue(java.util.Map.of(
                "offerNo", "OFR_1", "suggestedSalePriceCents", 9600L, "suggestPriceCents", 8000L,
                "unitPriceCents", 9500L), OfferListItem.class);
        var json = mapper.readTree(mapper.writeValueAsString(item));
        assertThat(json.get("suggestedSalePriceCents").longValue()).isEqualTo(9600L);
        assertThat(json.get("suggestPriceCents").longValue()).isEqualTo(8000L);
        SupplierAdminController.DraftReqVO request = mapper.readValue(
                "{\"platformSkuCode\":\"SKU_001_001\",\"priceMode\":\"DISCOUNT\",\"discountBp\":9000,"
                        + "\"supplyMode\":\"API_DIRECT\",\"externalSkuCode\":\"external\"}",
                SupplierAdminController.DraftReqVO.class);
        assertThat(request.getSuggestedPriceCents()).isNull();
        assertThat(SupplierAdminController.DraftReqVO.class.getDeclaredField("suggestedPriceCents")
                .getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class).description())
                .contains("兼容").contains("SKU");
    }

    @Test
    void simpleSupplierProjectionDocumentsActiveOnly() {
        Method page = Arrays.stream(SupplierAdminController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("page"))
                .findFirst()
                .orElseThrow();

        assertThat(page.getAnnotation(Operation.class).description())
                .contains("view=simple")
                .contains("ACTIVE")
                .contains("只返回正常");
    }

    @Test
    void saveReqSerializesCentsAndEnumNames() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        SupplierAdminController.SupplierSaveReqVO vo = new SupplierAdminController.SupplierSaveReqVO();
        vo.setSupplierName("甲");
        vo.setOpeningBalanceCents(100L);
        vo.setIntegrationModes(List.of("API"));
        String json = mapper.writeValueAsString(vo);
        assertThat(json).contains("openingBalanceCents").contains("\"API\"").doesNotContain("create");
        assertThat(json).doesNotContain("accountBalance");
    }

    @Test
    void controllerMappingsAreNouns() throws Exception {
        RequestMapping root = SupplierAdminController.class.getAnnotation(RequestMapping.class);
        assertThat(root.value()).containsExactly("/supply-chain/suppliers");
        assertThat(SupplierAdminController.class.getAnnotation(Tag.class)).isNotNull();
        boolean invoiceGet = Arrays.stream(SupplierAdminController.class.getDeclaredMethods())
                .anyMatch(method -> {
                    GetMapping get = method.getAnnotation(GetMapping.class);
                    return get != null && String.join(",", get.value()).equals("/{supplierCode}/invoice");
                });
        boolean invoicePut = Arrays.stream(SupplierAdminController.class.getDeclaredMethods())
                .anyMatch(method -> {
                    org.springframework.web.bind.annotation.PutMapping put =
                            method.getAnnotation(org.springframework.web.bind.annotation.PutMapping.class);
                    return put != null && String.join(",", put.value()).equals("/{supplierCode}/invoice");
                });
        assertThat(invoiceGet).isTrue();
        assertThat(invoicePut).isTrue();
        boolean invoiceGetSave = Arrays.stream(SupplierAdminController.class.getDeclaredMethods())
                .anyMatch(method -> joinedPaths(method).contains("invoice/get")
                        || joinedPaths(method).contains("invoice/save"));
        assertThat(invoiceGetSave).isFalse();
        boolean createPath = Arrays.stream(SupplierAdminController.class.getDeclaredMethods())
                .anyMatch(method -> {
                    PostMapping post = method.getAnnotation(PostMapping.class);
                    return post != null && String.join(",", post.value()).contains("create");
                });
        assertThat(createPath).isFalse();
        boolean simpleList = Arrays.stream(SupplierAdminController.class.getDeclaredMethods())
                .anyMatch(method -> joinedPaths(method).contains("simple-list"));
        assertThat(simpleList).isFalse();
        boolean patchMember = Arrays.stream(SupplierAdminController.class.getDeclaredMethods())
                .anyMatch(method -> {
                    PatchMapping patch = method.getAnnotation(PatchMapping.class);
                    return patch != null && String.join(",", patch.value()).contains("{supplierCode}");
                });
        assertThat(patchMember).isTrue();
        for (Method method : SupplierAdminController.class.getDeclaredMethods()) {
            if (method.isSynthetic() || !hasHttpMapping(method)) {
                continue;
            }
            assertThat(method.getAnnotation(Operation.class))
                    .as("%s 必须有 @Operation", method.getName())
                    .isNotNull();
        }
        boolean scopeBatch = Arrays.stream(SupplierAdminController.class.getDeclaredMethods())
                .anyMatch(method -> joinedPaths(method).contains("supply-scopes/batch"));
        assertThat(scopeBatch).isFalse();
        Method listScopes = SupplierAdminController.class.getDeclaredMethod("listScopes", String.class);
        assertThat(listScopes.getGenericReturnType().getTypeName()).contains("SupplyScopeView");
        assertThat(listScopes.getGenericReturnType().getTypeName()).doesNotContain("SupplierSupplyScopeDO");
        // 供货配置回显契约：SupplyScopeView 必须携带 categoryId（productClassification 为未归档
        // 类目编码时回填，封闭枚举名/已删除/已归档为 null），口径见 admin-frontend-api-design §5.4
        java.lang.reflect.Type listType = ((java.lang.reflect.ParameterizedType) listScopes.getGenericReturnType())
                .getActualTypeArguments()[0];
        Class<?> viewType = (Class<?>) ((java.lang.reflect.ParameterizedType) listType).getActualTypeArguments()[0];
        assertThat(viewType.getSimpleName()).isEqualTo("SupplyScopeView");
        java.lang.reflect.RecordComponent categoryId = Arrays.stream(viewType.getRecordComponents())
                .filter(component -> "categoryId".equals(component.getName()))
                .findFirst().orElse(null);
        assertThat(categoryId).as("SupplyScopeView 必须携带 categoryId 回显字段").isNotNull();
        // @Schema 无 RECORD_COMPONENT Target，注解会传播到 accessor 方法
        io.swagger.v3.oas.annotations.media.Schema schema = categoryId.getAccessor()
                .getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class);
        assertThat(schema).as("categoryId 必须带 @Schema 说明").isNotNull();
        assertThat(schema.description())
                .contains("未归档类目编码时回填").contains("归档即视为不可回显");
    }

    @Test
    void scopeReqSerializesSupplyModesArray() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SupplierAdminController.ScopeReqVO vo = new SupplierAdminController.ScopeReqVO();
        vo.setSellableCategory("MOVIE");
        vo.setProductType("MOVIE");
        vo.setProductClassification("MOVIE_TICKET");
        vo.setSupplyModes(List.of("API_DIRECT", "H5"));
        vo.setMaxQuantity(100L);
        vo.setMinQuantity(1L);
        String json = mapper.writeValueAsString(vo);
        assertThat(json).contains("supplyModes").contains("API_DIRECT").contains("H5");
        assertThat(json).doesNotContain("\"supplyMode\"");
    }

    @Test
    void offerCollectionUsesItemsAndDoesNotExposeDo() throws Exception {
        boolean postCollection = Arrays.stream(SupplierAdminController.class.getDeclaredMethods())
                .anyMatch(method -> {
                    PostMapping post = method.getAnnotation(PostMapping.class);
                    return post != null && String.join(",", post.value()).equals("/{supplierCode}/offers");
                });
        assertThat(postCollection).isTrue();
        Method listOffers = Arrays.stream(SupplierAdminController.class.getDeclaredMethods())
                .filter(method -> "listOffers".equals(method.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(listOffers.getGenericReturnType().getTypeName()).contains("OfferListItem");
        assertThat(listOffers.getGenericReturnType().getTypeName()).doesNotContain("SupplierOfferDO");
        SupplierAdminController.OfferItemsReqVO vo = new SupplierAdminController.OfferItemsReqVO();
        SupplierAdminController.DraftReqVO item = new SupplierAdminController.DraftReqVO();
        item.setPlatformSkuCode("MOVIE_100");
        item.setPriceMode("FIXED");
        item.setSupplyMode("API_DIRECT");
        vo.setItems(List.of(item));
        String json = new ObjectMapper().writeValueAsString(vo);
        assertThat(json).contains("items").contains("MOVIE_100");
        io.swagger.v3.oas.annotations.media.Schema priceModeSchema =
                SupplierAdminController.DraftReqVO.class.getDeclaredField("priceMode")
                        .getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class);
        assertThat(priceModeSchema.description()).contains("supply_chain_offer_price_mode");
        java.lang.reflect.RecordComponent listPriceMode =
                Arrays.stream(com.aifuli.module.supplychain.tradecore.supply.application
                                .SupplierCooperativeProductService.OfferListItem.class.getRecordComponents())
                        .filter(component -> "priceMode".equals(component.getName()))
                        .findFirst()
                        .orElseThrow();
        assertThat(listPriceMode.getAccessor()
                .getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class)
                .description())
                .contains("supply_chain_offer_price_mode");
    }

    @Test
    void fulfillmentScoresUseNounResourceAndOfferListExposesScores() throws Exception {
        boolean scoresPut = Arrays.stream(SupplierAdminController.class.getDeclaredMethods())
                .anyMatch(method -> {
                    org.springframework.web.bind.annotation.PutMapping put =
                            method.getAnnotation(org.springframework.web.bind.annotation.PutMapping.class);
                    return put != null
                            && String.join(",", put.value()).equals("/{supplierCode}/fulfillment-scores");
                });
        assertThat(scoresPut).isTrue();
        assertThat(Arrays.stream(SupplierAdminController.class.getDeclaredMethods())
                .filter(method -> {
                    org.springframework.web.bind.annotation.PutMapping put =
                            method.getAnnotation(org.springframework.web.bind.annotation.PutMapping.class);
                    return put != null
                            && String.join(",", put.value()).contains("fulfillment-scores");
                })
                .map(SupplierAdminRestContractTest::joinedPaths)
                .noneMatch(path -> path.contains("/batch"))).isTrue();
        assertThat(Arrays.stream(OfferListItem.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .contains("stabilityScore", "timelinessScore");
        java.lang.reflect.RecordComponent stability = Arrays.stream(OfferListItem.class.getRecordComponents())
                .filter(component -> "stabilityScore".equals(component.getName()))
                .findFirst()
                .orElseThrow();
        java.lang.reflect.RecordComponent timeliness = Arrays.stream(OfferListItem.class.getRecordComponents())
                .filter(component -> "timelinessScore".equals(component.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(stability.getAccessor().getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class)
                .description()).isEqualTo("稳定性评分，人工录入，0-100，默认80");
        assertThat(timeliness.getAccessor().getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class)
                .description()).isEqualTo("时效性评分，人工录入，0-100，默认80");
        assertScoreRange(SupplierAdminController.FulfillmentScoreSaveReqVO.class
                .getDeclaredField("stabilityScore"));
        assertScoreRange(SupplierAdminController.FulfillmentScoreSaveReqVO.class
                .getDeclaredField("timelinessScore"));
    }

    private static boolean hasHttpMapping(Method method) {
        return method.getAnnotation(RequestMapping.class) != null
                || method.getAnnotation(GetMapping.class) != null
                || method.getAnnotation(PostMapping.class) != null
                || method.getAnnotation(PatchMapping.class) != null
                || method.getAnnotation(org.springframework.web.bind.annotation.PutMapping.class) != null
                || method.getAnnotation(org.springframework.web.bind.annotation.DeleteMapping.class) != null;
    }

    private static String joinedPaths(Method method) {
        GetMapping get = method.getAnnotation(GetMapping.class);
        if (get != null) {
            return String.join(",", get.value());
        }
        PostMapping post = method.getAnnotation(PostMapping.class);
        if (post != null) {
            return String.join(",", post.value());
        }
        PatchMapping patch = method.getAnnotation(PatchMapping.class);
        if (patch != null) {
            return String.join(",", patch.value());
        }
        org.springframework.web.bind.annotation.PutMapping put =
                method.getAnnotation(org.springframework.web.bind.annotation.PutMapping.class);
        if (put != null) {
            return String.join(",", put.value());
        }
        org.springframework.web.bind.annotation.DeleteMapping delete =
                method.getAnnotation(org.springframework.web.bind.annotation.DeleteMapping.class);
        if (delete != null) {
            return String.join(",", delete.value());
        }
        return "";
    }

    private static void assertScoreRange(java.lang.reflect.Field field) {
        assertThat(field.getAnnotation(Min.class).value()).isEqualTo(OfferFulfillmentScore.MIN);
        assertThat(field.getAnnotation(Max.class).value()).isEqualTo(OfferFulfillmentScore.MAX);
    }
}
