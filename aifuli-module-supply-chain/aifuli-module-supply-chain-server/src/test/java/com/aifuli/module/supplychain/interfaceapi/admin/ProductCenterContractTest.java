package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.interfaceapi.admin.pricing.GenericPricingAdminVos;
import com.aifuli.module.supplychain.tradecore.catalog.application.BrandAdminService.BrandView;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogQueryService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogQueryService.SkuDetail;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogQueryService.SpuDetail;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogQueryService.SpuListItem;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductTypeAdminService.ProductTypeView;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BRAND_LEGACY_API_DEPRECATED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_STATUS_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 商品中心管理端 OpenAPI 契约与接口治理门禁。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#36-openapiswagger}。
 */
class ProductCenterContractTest {

    @Test
    void productAdminControllerDeclaresTagAndOperations() {
        assertThat(ProductAdminController.class.getAnnotation(Tag.class)).isNotNull();
        assertThat(ProductAdminController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/supply-chain/products");

        for (Method method : ProductAdminController.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            if (isHttpEndpoint(method)) {
                assertThat(method.getAnnotation(Operation.class))
                        .as("ProductAdminController#%s 缺少 @Operation", method.getName())
                        .isNotNull();
                assertThat(method.getAnnotation(PreAuthorize.class))
                        .as("ProductAdminController#%s 缺少 @PreAuthorize", method.getName())
                        .isNotNull();
            }
        }
    }

    /** 商品复合保存只接受业务履约类型，内部 Schema 不得重新暴露为可写请求字段。 */
    @Test
    void productSaveRequestDoesNotExposeFulfillmentSchemas() {
        assertThat(Arrays.stream(ProductAdminController.SkuItemSaveReqVO.class.getDeclaredFields())
                .map(field -> field.getName()))
                .doesNotContain("targetSchemaCode", "targetSchemaVersion", "resultSchemaCode", "resultSchemaVersion")
                .contains("mainImageUrl", "detailImageUrls", "description",
                        "suggestedSalePrice", "suggestedSalePriceRule",
                        "fulfillmentType", "taxIncluded", "taxRate",
                        "userLimitEnabled", "userLimitPeriod", "userLimitQuantity", "orderLimitEnabled",
                        "phoneBindLimitEnabled", "phoneBindLimitPeriod", "phoneBindLimitQuantity",
                        "skuInventory");
    }

    /** SKU 展示资料字段注释必须与 034 DDL COMMENT 完全一致。 */
    @Test
    void skuDisplayContentCommentsMatchDdl() throws Exception {
        assertThat(ProductAdminController.SkuItemSaveReqVO.class.getDeclaredField("mainImageUrl")
                .getAnnotation(Schema.class).description()).isEqualTo("SKU主图地址");
        assertThat(ProductAdminController.SkuItemSaveReqVO.class.getDeclaredField("detailImageUrls")
                .getAnnotation(Schema.class).description()).isEqualTo("SKU详情图地址列表(JSON数组)");
        assertThat(ProductAdminController.SkuItemSaveReqVO.class.getDeclaredField("description")
                .getAnnotation(Schema.class).description()).isEqualTo("SKU商品描述");
    }

    /**
     * skuInventory 字段注释必须与 041 DDL COMMENT 完全一致。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d38）。
     */
    @Test
    void skuInventoryCommentsMatchDdl() throws Exception {
        String comment = "SKU库存数量，管理端创建或编辑商品时设置";
        assertThat(ProductAdminController.SkuItemSaveReqVO.class.getDeclaredField("skuInventory")
                .getAnnotation(Schema.class).description()).isEqualTo(comment);
        assertThat(SkuDetail.class.getDeclaredField("skuInventory")
                .getAnnotation(Schema.class).description()).isEqualTo(comment);
        assertThat(ProductCatalogQueryService.ProductManagementSkuItem.class
                .getDeclaredMethod("skuInventory")
                .getAnnotation(Schema.class).description()).isEqualTo(comment);
        Map<String, io.swagger.v3.oas.models.media.Schema> detailSchemas =
                ModelConverters.getInstance().readAll(SkuDetail.class);
        io.swagger.v3.oas.models.media.Schema<?> skuInventorySchema =
                (io.swagger.v3.oas.models.media.Schema<?>) detailSchemas.get("SkuDetail")
                        .getProperties().get("skuInventory");
        assertThat(skuInventorySchema.getDescription()).isEqualTo(comment);
    }

    /** 销售建议价规则只随复合保存写入，并在详情中完整回显，不得保留独立旁路接口。 */
    @Test
    void suggestedSalePriceRuleIsExposedByCompositeSaveAndDetailContracts() throws Exception {
        assertThat(fieldNames(ProductAdminController.SuggestedSalePriceRuleReqVO.class))
                .containsExactlyInAnyOrder("mode", "base", "percentage", "operator", "operand",
                        "fixedPriceCents");
        assertThat(fieldNames(ProductAdminController.SkuItemSaveReqVO.class))
                .contains("suggestedSalePrice", "suggestedSalePriceRule");
        assertThat(Arrays.stream(ProductAdminController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PutMapping.class))
                .flatMap(method -> Arrays.stream(method.getAnnotation(PutMapping.class).value())))
                .doesNotContain("/skus/suggested-sale-price");
        assertThat(Arrays.stream(SkuDetail.class.getRecordComponents()).map(component -> component.getName()))
                .contains("suggestedSalePrice", "suggestedSalePriceRule");
        assertRecordComponentsDocumented(
                com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogQueryService
                        .SuggestedSalePriceRuleView.class);
        assertThat(ProductAdminController.SuggestedSalePriceRuleReqVO.class.getDeclaredField("mode")
                .getAnnotation(Schema.class).description())
                .contains("supply_chain_suggested_sale_price_mode");
        assertThat(ProductAdminController.SuggestedSalePriceRuleReqVO.class.getDeclaredField("operator")
                .getAnnotation(Schema.class).description())
                .contains("supply_chain_suggested_sale_price_operator");
        Map<String, io.swagger.v3.oas.models.media.Schema> ruleSchemas = ModelConverters.getInstance()
                .readAll(com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogQueryService
                        .SuggestedSalePriceRuleView.class);
        io.swagger.v3.oas.models.media.Schema<?> modeSchema =
                (io.swagger.v3.oas.models.media.Schema<?>) ruleSchemas.get("SuggestedSalePriceRuleView")
                        .getProperties().get("mode");
        assertThat(modeSchema.getDescription())
                .contains("supply_chain_suggested_sale_price_mode");
        io.swagger.v3.oas.models.media.Schema<?> operatorSchema =
                (io.swagger.v3.oas.models.media.Schema<?>) ruleSchemas.get("SuggestedSalePriceRuleView")
                        .getProperties().get("operator");
        assertThat(operatorSchema.getDescription())
                .contains("supply_chain_suggested_sale_price_operator");
    }

    /**
     * 复合创建、编辑接口共用的三层请求模型，每个字段都必须提供可直接联调的非空 Swagger 示例。
     * 完整 SKU 示例还必须覆盖全部嵌套字段，避免只给出空数组或不完整对象。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#41-接口定义}。
     */
    @Test
    void compositeSaveRequestFieldsDeclareConcreteSwaggerExamples() throws Exception {
        assertEveryFieldHasConcreteExample(ProductAdminController.SpuSaveReqVO.class);
        assertEveryFieldHasConcreteExample(ProductAdminController.SkuItemSaveReqVO.class);
        assertEveryFieldHasConcreteExample(ProductAdminController.SuggestedSalePriceRuleReqVO.class);

        String createExample = requestBodyExample(ProductAdminController.class.getDeclaredMethod(
                "create", ProductAdminController.SpuSaveReqVO.class));
        String updateExample = requestBodyExample(ProductAdminController.class.getDeclaredMethod(
                "update", String.class, ProductAdminController.SpuSaveReqVO.class));
        assertThat(updateExample).isEqualTo(createExample);

        JsonNode request = new ObjectMapper().readTree(createExample);
        assertThat(jsonFieldNames(request))
                .containsExactlyInAnyOrder(fieldNames(ProductAdminController.SpuSaveReqVO.class));
        JsonNode skus = request.get("skus");
        assertThat(skus.isArray()).isTrue();
        assertThat(skus.size()).isGreaterThan(0);

        JsonNode sku = skus.get(0);
        assertThat(jsonFieldNames(sku))
                .containsExactlyInAnyOrder(fieldNames(ProductAdminController.SkuItemSaveReqVO.class));

        JsonNode rule = sku.get("suggestedSalePriceRule");
        assertThat(rule).isNotNull();
        assertThat(jsonFieldNames(rule))
                .containsExactlyInAnyOrder(fieldNames(ProductAdminController.SuggestedSalePriceRuleReqVO.class));

        assertThat(sku.get("marketPrice").asLong()).isEqualTo(12_000L);
        assertThat(rule.get("percentage").decimalValue()).isEqualByComparingTo("50");
        assertThat(rule.get("operator").asText()).isEqualTo("ADD");
        assertThat(rule.get("operand").decimalValue()).isEqualByComparingTo("1.00");
        assertThat(sku.get("suggestedSalePrice").asLong()).isEqualTo(6_100L);
        assertThat(sku.get("skuInventory").asLong()).isEqualTo(1000L);
    }

    /** 原型商品分类绑定分类树主键；供货命中码可同时接受字典枚举名或类目编码。 */
    @Test
    void productCategoryUsesRequiredPrimaryCategoryId() throws Exception {
        var categoryField = ProductAdminController.SpuSaveReqVO.class.getDeclaredField("primaryCategoryId");
        assertThat(categoryField.isAnnotationPresent(NotNull.class)).isTrue();
        assertThat(categoryField.getAnnotation(Schema.class).description())
                .contains("/supply-chain/categories", "不是system数据字典");
        assertThat(ProductAdminController.SpuSaveReqVO.class.getDeclaredField("productClassification")
                .getAnnotation(Schema.class).description())
                .contains("supply_chain_product_classification", "categoryCode");
    }

    /**
     * 管理端商品表单会把 SKU 主图/详情图写成 picUrl、detailPics；复合保存必须收下这些别名，
     * 才能把请求里的展示资料完整落入 main_image_url / detail_image_urls。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d35）。
     */
    @Test
    void createRequestAcceptsSkuImageAliasesAndSpuLevelLimits() throws Exception {
        ProductAdminController.SpuSaveReqVO request = new ObjectMapper().readValue("""
                {
                  "spuName":"测试777",
                  "productType":"MOVIE",
                  "primaryCategoryId":3,
                  "productClassification":"E2E_L3_20260903",
                  "sellableCategory":"EQUITY",
                  "deliveryType":"VIRTUAL",
                  "status":"0",
                  "fulfillmentType":"DIRECT_RECHARGE",
                  "userLimitEnabled":true,
                  "userLimitPeriod":"DAY",
                  "userLimitQuantity":1,
                  "phoneBindLimitEnabled":true,
                  "phoneBindLimitQuantity":1,
                  "skus":[{
                    "skuCode":"",
                    "skuName":"测试名称",
                    "picUrl":"https://cdn.example.com/main.png",
                    "detailPics":["https://cdn.example.com/detail.png"],
                    "description":"SKU商品描述",
                    "marketPrice":9700,
                    "faceValue":10000,
                    "skuInventory":1000,
                    "suggestedSalePriceRule":{
                      "mode":"CUSTOM",
                      "base":"MARKET_PRICE",
                      "percentage":100,
                      "operator":"ADD",
                      "operand":8
                    }
                  }],
                  "brandCode":"1111"
                }
                """, ProductAdminController.SpuSaveReqVO.class);

        assertThat(request.getStatus()).isEqualTo((short) 0);
        assertThat(request.getUserLimitEnabled()).isTrue();
        assertThat(request.getUserLimitPeriod()).isEqualTo("DAY");
        assertThat(request.getUserLimitQuantity()).isEqualTo(1L);
        assertThat(request.getPhoneBindLimitEnabled()).isTrue();
        assertThat(request.getPhoneBindLimitQuantity()).isEqualTo(1L);
        assertThat(request.getSkus()).singleElement().satisfies(sku -> {
            assertThat(sku.getMainImageUrl()).isEqualTo("https://cdn.example.com/main.png");
            assertThat(sku.getDetailImageUrls()).containsExactly("https://cdn.example.com/detail.png");
            assertThat(sku.getDescription()).isEqualTo("SKU商品描述");
            assertThat(sku.getSkuInventory()).isEqualTo(1000L);
        });

        ProductCatalogCommandService.SaveSpuCommand command =
                ProductAdminController.toSaveCommand(request, 1L);
        assertThat(command.status()).isEqualTo((short) 0);
        assertThat(command.skus()).singleElement().satisfies(sku -> {
            assertThat(sku.mainImageUrl()).isEqualTo("https://cdn.example.com/main.png");
            assertThat(sku.detailImageUrls()).containsExactly("https://cdn.example.com/detail.png");
            assertThat(sku.description()).isEqualTo("SKU商品描述");
            assertThat(sku.userLimitEnabled()).isTrue();
            assertThat(sku.userLimitPeriod()).isEqualTo("DAY");
            assertThat(sku.userLimitQuantity()).isEqualTo(1L);
            assertThat(sku.phoneBindLimitEnabled()).isTrue();
            assertThat(sku.phoneBindLimitPeriod()).isEqualTo("DAY");
            assertThat(sku.phoneBindLimitQuantity()).isEqualTo(1L);
            assertThat(sku.skuInventory()).isEqualTo(1000L);
            assertThat(sku.suggestedSalePriceRule()).isNotNull();
            assertThat(sku.suggestedSalePriceRule().mode().name()).isEqualTo("CUSTOM");
        });
    }

    /**
     * 管理端编辑表单按 picUrl、detailPics 回填图片。详情若只回 mainImageUrl、detailImageUrls，
     * 表单会当成没图，下一次全量保存把已入库图片写成 NULL。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d35）。
     */
    @Test
    void skuDetailSerializesFrontendImageAliasesForFormRefill() throws Exception {
        SkuDetail sku = new SkuDetail("SKU_1", "规格",
                "https://cdn.example.com/main.png", List.of("https://cdn.example.com/detail.png"),
                "描述", "DIRECT_RECHARGE",
                10_000L, 9_700L, 10_500L, null, false, null, 1L, 10L,
                true, "DAY", 1L, false, true, "DAY", 1L, 1000L,
                "DIRECT_RECHARGE_TARGET", 1, "DIRECT_RECHARGE_RESULT", 1, (short) 0, false);

        JsonNode json = new ObjectMapper().valueToTree(sku);

        assertThat(json.get("mainImageUrl").asText()).isEqualTo("https://cdn.example.com/main.png");
        assertThat(json.get("picUrl").asText()).isEqualTo("https://cdn.example.com/main.png");
        assertThat(json.get("detailImageUrls").get(0).asText()).isEqualTo("https://cdn.example.com/detail.png");
        assertThat(json.get("detailPics").get(0).asText()).isEqualTo("https://cdn.example.com/detail.png");
        assertThat(json.get("skuInventory").asLong()).isEqualTo(1000L);
    }

    /**
     * 商品管理返回给前端的数据字典码必须是字符串，与 system 字典 value（如 "0"/"10"/"20"）对齐。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d36）。
     */
    @Test
    void productManagementDictFieldsSerializeAsStrings() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SkuDetail sku = new SkuDetail("SKU_1", "规格", null, null, "描述", "DIRECT_RECHARGE",
                10_000L, 9_700L, 10_500L, null, false, null, 1L, 10L,
                true, "DAY", 1L, false, true, "DAY", 1L, 0L,
                "DIRECT_RECHARGE_TARGET", 1, "DIRECT_RECHARGE_RESULT", 1, (short) 0, false);
        JsonNode skuJson = mapper.valueToTree(sku);
        assertThat(skuJson.get("status").isTextual()).isTrue();
        assertThat(skuJson.get("status").asText()).isEqualTo("0");

        SpuListItem listItem = new SpuListItem("SPU_1", "商品", "品牌", "分类", "EQUITY", "MOVIE",
                "VIRTUAL_CARD", null, (short) 10, 1, null, null, null);
        JsonNode listJson = mapper.valueToTree(listItem);
        assertThat(listJson.get("status").isTextual()).isTrue();
        assertThat(listJson.get("status").asText()).isEqualTo("10");

        var managementSku = new ProductCatalogQueryService.ProductManagementSkuItem(
                "SKU_1", "规格", 9_700L, 10_500L, null, null, null, false, null, null, null,
                List.of(), 0L, "DIRECT_RECHARGE", "虚拟直充", (short) 20);
        JsonNode managementSkuJson = mapper.valueToTree(managementSku);
        assertThat(managementSkuJson.get("status").isTextual()).isTrue();
        assertThat(managementSkuJson.get("status").asText()).isEqualTo("20");
        assertThat(managementSkuJson.get("totalInventory").asLong()).isEqualTo(0L);
        assertThat(managementSkuJson.get("skuInventory").asLong()).isEqualTo(0L);

        var managementSpu = new ProductCatalogQueryService.ProductManagementSpuItem(
                "SPU_1", "商品", null, "MOVIE", "电影", "分类", "品牌", (short) 0,
                null, null, null, List.of());
        JsonNode managementSpuJson = mapper.valueToTree(managementSpu);
        assertThat(managementSpuJson.get("status").isTextual()).isTrue();
        assertThat(managementSpuJson.get("status").asText()).isEqualTo("0");

        Map<String, io.swagger.v3.oas.models.media.Schema> detailSchemas =
                ModelConverters.getInstance().readAll(SpuDetail.class);
        io.swagger.v3.oas.models.media.Schema<?> statusSchema =
                (io.swagger.v3.oas.models.media.Schema<?>) detailSchemas.get("SpuDetail")
                        .getProperties().get("status");
        assertThat(statusSchema.getType()).isEqualTo("string");
    }

    /** SKU 限购请求字段注释必须与 DDL COMMENT 使用同一表述。 */
    @Test
    void skuPurchaseLimitRequestCommentsMatchDdl() throws Exception {
        assertThat(ProductAdminController.SkuItemSaveReqVO.class.getDeclaredField("maxQuantity")
                .getAnnotation(Schema.class).description()).isEqualTo("SKU单次最大限购数量");
        assertThat(ProductAdminController.SkuItemSaveReqVO.class.getDeclaredField("userLimitEnabled")
                .getAnnotation(Schema.class).description()).isEqualTo("是否启用每人限购：1启用，0停用");
        assertThat(ProductAdminController.SkuItemSaveReqVO.class.getDeclaredField("userLimitPeriod")
                .getAnnotation(Schema.class).description())
                .isEqualTo("每人限购周期：LIFETIME终身，YEAR每年，MONTH每月，WEEK每周，DAY每天");
        assertThat(ProductAdminController.SkuItemSaveReqVO.class.getDeclaredField("userLimitQuantity")
                .getAnnotation(Schema.class).description()).isEqualTo("每人每周期最多购买数量");
        assertThat(ProductAdminController.SkuItemSaveReqVO.class.getDeclaredField("orderLimitEnabled")
                .getAnnotation(Schema.class).description()).isEqualTo("是否启用每订单限购：1启用，0停用");
        assertThat(ProductAdminController.SkuItemSaveReqVO.class.getDeclaredField("phoneBindLimitEnabled")
                .getAnnotation(Schema.class).description()).isEqualTo("是否启用绑定手机号限购：1启用，0停用");
        assertThat(ProductAdminController.SkuItemSaveReqVO.class.getDeclaredField("phoneBindLimitPeriod")
                .getAnnotation(Schema.class).description())
                .isEqualTo("绑定手机号限购周期：LIFETIME终身，YEAR每年，MONTH每月，WEEK每周，DAY每天");
        assertThat(ProductAdminController.SkuItemSaveReqVO.class.getDeclaredField("phoneBindLimitQuantity")
                .getAnnotation(Schema.class).description()).isEqualTo("同一履约手机号每周期最多购买数量");
    }

    /** 平台 SKU 保留旧展示元数据；通用定价按分销商/项目维度使用含税标志与动态税率字典码。 */
    @Test
    void taxMetadataIsSeparatedFromLayeredPricingRequests() throws Exception {
        assertThat(fieldNames(ProductAdminController.SkuTaxMetadataBatchReqVO.class))
                .containsExactlyInAnyOrder("skuCodes", "taxIncluded", "taxRate");
        assertThat(fieldNames(BuyerAdminController.BuyerProductBatchPricingReqVO.class))
                .contains("taxIncluded", "taxRateCode")
                .doesNotContain("taxRate");
        assertThat(fieldNames(ProjectAdminController.ProjectProductBatchPricingReqVO.class))
                .contains("taxInherited", "taxMetadata")
                .doesNotContain("taxRate");
        assertThat(fieldNames(GenericPricingAdminVos.TaxMetadataReqVO.class))
                .containsExactlyInAnyOrder("taxIncluded", "taxRateCode");
        assertThat(BuyerAdminController.BuyerProductBatchPricingReqVO.class.getDeclaredField("taxIncluded").getType())
                .isEqualTo(Boolean.class);
        assertThat(BuyerAdminController.BuyerProductBatchPricingReqVO.class.getDeclaredField("taxIncluded")
                .getAnnotation(NotNull.class)).isNull();
        assertThat(GenericPricingAdminVos.TaxMetadataReqVO.class.getDeclaredField("taxIncluded").getType())
                .isEqualTo(Boolean.class);
        assertThat(GenericPricingAdminVos.TaxMetadataReqVO.class.getDeclaredField("taxIncluded")
                .getAnnotation(NotNull.class)).isNull();
        assertThat(ProductAdminController.SkuItemSaveReqVO.class.getDeclaredField("taxIncluded")
                .getAnnotation(Schema.class).description())
                .isEqualTo("是否含税：1含税，0不含税；仅记录展示，不参与价格计算；标准商品结算以分销商/项目通用定价为准");
        assertThat(ProductAdminController.SkuItemSaveReqVO.class.getDeclaredField("taxRate")
                .getAnnotation(Schema.class).description())
                .isEqualTo("税率，如0.0600表示6%；仅记录展示，不参与价格计算；标准商品结算以分销商/项目通用定价为准");
    }

    /** 所有管理端写入口都不得要求页面决定内部 Schema 编码或版本。 */
    @Test
    void adminWriteRequestsDoNotExposeInternalSchemas() {
        assertThat(fieldNames(M1MasterDataAdminController.CreateSkuReqVO.class))
                .doesNotContain("targetSchemaCode", "targetSchemaVersion", "resultSchemaCode", "resultSchemaVersion")
                .contains("fulfillmentType");
        assertThat(fieldNames(SupplierConfigurationAdminController.CreateConnectorDraftReqVO.class))
                .doesNotContain("adapterConfigSchemaVersion")
                .contains("adapterType", "adapterConfigJson");
        assertThat(fieldNames(SupplierConfigurationAdminController.PublishOfferReqVO.class))
                .doesNotContain("fulfillmentConfigSchemaVersion")
                .contains("connectorVersionId", "fulfillmentConfigJson");
    }

    @Test
    void brandAdminControllerDeclaresTagAndOperations() {
        assertThat(BrandAdminController.class.getAnnotation(Tag.class)).isNotNull();
        assertThat(BrandAdminController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/supply-chain/brands");

        for (Method method : BrandAdminController.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            if (isHttpEndpoint(method)) {
                assertThat(method.getAnnotation(Operation.class))
                        .as("BrandAdminController#%s 缺少 @Operation", method.getName())
                        .isNotNull();
                assertThat(method.getAnnotation(PreAuthorize.class))
                        .as("BrandAdminController#%s 缺少 @PreAuthorize", method.getName())
                        .isNotNull();
            }
        }
    }

    @Test
    void productTypeAdminControllerDeclaresTagAndOperations() {
        assertThat(ProductTypeAdminController.class.getAnnotation(Tag.class)).isNotNull();
        assertThat(ProductTypeAdminController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/supply-chain/product-types");

        for (Method method : ProductTypeAdminController.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            if (isHttpEndpoint(method)) {
                assertThat(method.getAnnotation(Operation.class))
                        .as("ProductTypeAdminController#%s 缺少 @Operation", method.getName())
                        .isNotNull();
                assertThat(method.getAnnotation(PreAuthorize.class))
                        .as("ProductTypeAdminController#%s 缺少 @PreAuthorize", method.getName())
                        .isNotNull();
            }
        }
    }

    /** Query、Path 参数与响应字段都必须可在 Knife4j 中直接识别，避免联调依赖阅读 Java 源码。 */
    @Test
    void productCenterDocumentsParametersAndResponseFields() {
        assertEndpointParametersDocumented(ProductAdminController.class);
        assertEndpointParametersDocumented(BrandAdminController.class);
        assertEndpointParametersDocumented(ProductTypeAdminController.class);

        assertRecordComponentsDocumented(SpuListItem.class);
        assertRecordComponentsDocumented(SpuDetail.class);
        assertRecordComponentsDocumented(SkuDetail.class);
        assertRecordComponentsDocumented(BrandView.class);
        assertRecordComponentsDocumented(ProductTypeView.class);
        assertRecordComponentsDocumented(com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogQueryService.ProductManagementSpuItem.class);
        assertRecordComponentsDocumented(com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogQueryService.ProductManagementSkuItem.class);
        assertRecordComponentsDocumented(com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogQueryService.SupplierSimpleInfo.class);
    }

    /** 商品列表两个字典筛选参数必须明确 SKU 状态口径及对应字典类型。 */
    @Test
    void managementPageDocumentsSkuStatusAndFulfillmentDictionaries() {
        Method managementPage = Arrays.stream(ProductAdminController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("managementPage"))
                .findFirst()
                .orElseThrow();
        Map<String, String> descriptions = Arrays.stream(managementPage.getParameters())
                .collect(java.util.stream.Collectors.toMap(
                        java.lang.reflect.Parameter::getName,
                        parameter -> parameter.getAnnotation(Parameter.class).description()));

        assertThat(descriptions.get("status"))
                .contains("SKU商品状态", "supply_chain_sku_status");
        assertThat(descriptions.get("fulfillmentType"))
                .contains("CARD_CODE_SECRET", "DIRECT_RECHARGE", "supply_chain_fulfillment_type");
    }

    /**
     * 管理端聚合列表商品类型筛选必须声明为可多选 List，并兼容单个 productType。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d39）。
     */
    @Test
    void managementPageProductTypeAcceptsMultipleValues() {
        Method managementPage = Arrays.stream(ProductAdminController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("managementPage"))
                .findFirst()
                .orElseThrow();
        java.lang.reflect.Parameter productType = Arrays.stream(managementPage.getParameters())
                .filter(parameter -> parameter.getName().equals("productType"))
                .findFirst()
                .orElseThrow();

        assertThat(List.class.isAssignableFrom(productType.getType())).isTrue();
        assertThat(productType.getAnnotation(Parameter.class).description())
                .contains("可多选")
                .contains("兼容");
    }

    /**
     * 商品类型管理端 OpenAPI 参数与模型字段必须明确状态口径及对应字典类型，且去除历史笔误（如0草稿）。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#53-商品中心数据字典与配置变更}。
     */
    @Test
    @SuppressWarnings("rawtypes")
    void productTypeDocumentsStatusDictionary() throws Exception {
        Method pageMethod = Arrays.stream(ProductTypeAdminController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("page"))
                .findFirst()
                .orElseThrow();
        Map<String, String> paramDescriptions = Arrays.stream(pageMethod.getParameters())
                .filter(p -> p.isAnnotationPresent(Parameter.class))
                .collect(java.util.stream.Collectors.toMap(
                        java.lang.reflect.Parameter::getName,
                        p -> p.getAnnotation(Parameter.class).description()));

        assertThat(paramDescriptions.get("status"))
                .contains("supply_chain_product_type_status", "10启用", "20禁用")
                .doesNotContain("0草稿", "草稿");

        var saveStatusField = ProductTypeAdminController.ProductTypeSaveReqVO.class.getDeclaredField("status");
        assertThat(saveStatusField.getAnnotation(Schema.class).description())
                .contains("supply_chain_product_type_status");

        Map<String, io.swagger.v3.oas.models.media.Schema> schemas =
                ModelConverters.getInstance().readAll(ProductTypeView.class);
        io.swagger.v3.oas.models.media.Schema<?> productTypeViewSchema = schemas.get("ProductTypeView");
        assertThat(productTypeViewSchema).isNotNull();
        io.swagger.v3.oas.models.media.Schema<?> statusProperty =
                (io.swagger.v3.oas.models.media.Schema<?>) productTypeViewSchema.getProperties().get("status");
        assertThat(statusProperty.getDescription())
                .contains("supply_chain_product_type_status");
    }

    @Test
    void legacyMasterDataCatalogEndpointsAreDeprecated() throws NoSuchMethodException {
        Method legacyBrandMethod = M1MasterDataAdminController.class.getMethod(
                "createBrand", M1MasterDataAdminController.CreateBrandReqVO.class);
        assertThat(legacyBrandMethod.isAnnotationPresent(Deprecated.class)).isTrue();
        assertThat(legacyBrandMethod.getAnnotation(Operation.class).deprecated()).isTrue();
        assertThat(legacyBrandMethod.getParameters()[0].isAnnotationPresent(Valid.class)).isFalse();
        assertThat(legacyBrandMethod.getParameters()[0].getAnnotation(RequestBody.class).required()).isFalse();
        assertThat(M1MasterDataAdminController.class.getMethod("createCategory", M1MasterDataAdminController.CreateCategoryReqVO.class)
                .isAnnotationPresent(Deprecated.class)).isTrue();
        assertThat(M1MasterDataAdminController.class.getMethod("createSpu", M1MasterDataAdminController.CreateSpuReqVO.class)
                .isAnnotationPresent(Deprecated.class)).isTrue();
        assertThat(M1MasterDataAdminController.class.getMethod("createSku", M1MasterDataAdminController.CreateSkuReqVO.class)
                .isAnnotationPresent(Deprecated.class)).isTrue();
        assertThat(M1MasterDataAdminController.class.getMethod("changeCatalogEntityStatus", M1MasterDataAdminController.ChangeCatalogEntityStatusReqVO.class)
                .isAnnotationPresent(Deprecated.class)).isTrue();
    }

    /**
     * 旧品牌创建入口必须固定拒绝请求，禁止再绕过新接口的 Logo 必填约束写入品牌。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#45-存量主数据接口治理m1masterdataadmincontroller}。
     */
    @Test
    void legacyBrandCreateEndpointAlwaysReturnsDeprecatedError() {
        M1MasterDataAdminController controller = new M1MasterDataAdminController(null, null, null);
        M1MasterDataAdminController.CreateBrandReqVO request = new M1MasterDataAdminController.CreateBrandReqVO();

        assertThatThrownBy(() -> controller.createBrand(null))
                .isInstanceOf(ServiceException.class)
                .hasMessage(BRAND_LEGACY_API_DEPRECATED.getMsg())
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(BRAND_LEGACY_API_DEPRECATED.getCode());
        assertThatThrownBy(() -> controller.createBrand(request))
                .isInstanceOf(ServiceException.class)
                .hasMessage(BRAND_LEGACY_API_DEPRECATED.getMsg())
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(BRAND_LEGACY_API_DEPRECATED.getCode());
    }

    /**
     * 商品删除必须自带事务边界：先前实现靠自调用 {@code changeSpuStatus} 触发事务，会被 Spring 代理绕过，
     * 导致行锁、4 表引用校验与归档写入分处不同事务。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#32-删除强校验规则4-表物理主键与关联路径检查}。
     */
    @Test
    void productDeletionEntryPointsDeclareOwnTransaction() throws NoSuchMethodException {
        assertThat(ProductCatalogCommandService.class
                .getMethod("deleteSpu", String.class, Long.class)
                .getAnnotation(Transactional.class))
                .as("deleteSpu 必须自带 @Transactional，不能依赖自调用触发事务")
                .isNotNull();
        assertThat(ProductCatalogCommandService.class
                .getMethod("deleteSku", String.class, Long.class)
                .getAnnotation(Transactional.class))
                .as("deleteSku 必须自带 @Transactional，不能依赖自调用触发事务")
                .isNotNull();
    }

    /** 四类实体的状态端点共用同一请求体，且只接受启用/停用，归档必须走 DELETE 以触发引用强校验。 */
    @Test
    void statusEndpointsShareSwitchOnlyRequestBody() {
        CatalogStatusSwitchReqVO request = new CatalogStatusSwitchReqVO();
        request.setTargetStatus(CatalogEntityStatus.ACTIVE.getCode());
        assertThat(request.requireSwitchTarget()).isEqualTo(CatalogEntityStatus.ACTIVE);

        request.setTargetStatus(CatalogEntityStatus.ARCHIVED.getCode());
        assertThatThrownBy(request::requireSwitchTarget)
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PRODUCT_STATUS_INVALID.getCode());

        request.setTargetStatus((short) 7);
        assertThatThrownBy(request::requireSwitchTarget)
                .as("未知状态码也必须映射为业务错误码，不能抛裸 IllegalArgumentException")
                .isInstanceOf(ServiceException.class);
    }

    private static boolean isHttpEndpoint(Method method) {
        return method.isAnnotationPresent(RequestMapping.class)
                || Arrays.stream(method.getDeclaredAnnotations())
                .anyMatch(ann -> ann.annotationType().isAnnotationPresent(RequestMapping.class));
    }

    private static String[] fieldNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields()).map(field -> field.getName()).toArray(String[]::new);
    }

    private static Set<String> jsonFieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String requestBodyExample(Method method) {
        java.lang.reflect.Parameter requestParameter = Arrays.stream(method.getParameters())
                .filter(parameter -> parameter.isAnnotationPresent(
                        io.swagger.v3.oas.annotations.parameters.RequestBody.class))
                .findFirst()
                .orElseThrow();
        io.swagger.v3.oas.annotations.parameters.RequestBody requestBody = requestParameter.getAnnotation(
                io.swagger.v3.oas.annotations.parameters.RequestBody.class);
        assertThat(requestBody.required()).isTrue();
        assertThat(requestBody.content()).hasSize(1);
        assertThat(requestBody.content()[0].examples()).hasSize(1);
        String example = requestBody.content()[0].examples()[0].value();
        assertThat(example).isNotBlank();
        return example;
    }

    @SuppressWarnings("rawtypes")
    private static void assertEveryFieldHasConcreteExample(Class<?> type) {
        Arrays.stream(type.getDeclaredFields()).forEach(field -> {
            Schema schema = field.getAnnotation(Schema.class);
            assertThat(schema)
                    .as("%s.%s 缺少 @Schema", type.getSimpleName(), field.getName())
                    .isNotNull();
            assertThat(schema.example())
                    .as("%s.%s 缺少可直接联调的 Swagger 示例", type.getSimpleName(), field.getName())
                    .isNotBlank()
                    .isNotIn("null", "\"\"", "[]", "{}");
        });

        Map<String, io.swagger.v3.oas.models.media.Schema> schemas =
                ModelConverters.getInstance().readAll(type);
        io.swagger.v3.oas.models.media.Schema<?> model = schemas.get(type.getSimpleName());
        assertThat(model).as("未生成 OpenAPI schema %s", type.getSimpleName()).isNotNull();
        assertThat(model.getProperties().keySet()).containsExactlyInAnyOrder(fieldNames(type));
        model.getProperties().forEach((fieldName, property) -> {
            Object example = property.getExample();
            if (example == null && property.get$ref() != null) {
                assertThat(property.get$ref())
                        .as("OpenAPI schema %s.%s 的引用模型不存在", type.getSimpleName(), fieldName)
                        .startsWith("#/components/schemas/");
                return;
            }
            assertThat(example)
                    .as("生成的 OpenAPI schema %s.%s 缺少示例", type.getSimpleName(), fieldName)
                    .isNotNull();
            assertThat(example.toString())
                    .as("生成的 OpenAPI schema %s.%s 示例为空", type.getSimpleName(), fieldName)
                    .isNotBlank()
                    .isNotIn("null", "\"\"", "[]", "{}");
        });
    }

    private static void assertEndpointParametersDocumented(Class<?> controllerType) {
        Arrays.stream(controllerType.getDeclaredMethods())
                .filter(ProductCenterContractTest::isHttpEndpoint)
                .flatMap(method -> Arrays.stream(method.getParameters()))
                .filter(parameter -> parameter.isAnnotationPresent(RequestParam.class)
                        || parameter.isAnnotationPresent(PathVariable.class))
                .forEach(parameter -> {
                    Parameter document = parameter.getAnnotation(Parameter.class);
                    assertThat(document)
                            .as("%s 的 Query/Path 参数 %s 缺少 @Parameter",
                                    controllerType.getSimpleName(), parameter.getName())
                            .isNotNull();
                    assertThat(document.description()).isNotBlank();
                });
    }

    @SuppressWarnings("rawtypes")
    private static void assertRecordComponentsDocumented(Class<?> recordType) {
        assertThat(recordType.isRecord()).isTrue();
        Map<String, io.swagger.v3.oas.models.media.Schema> schemas =
                ModelConverters.getInstance().readAll(recordType);
        io.swagger.v3.oas.models.media.Schema<?> schema = schemas.get(recordType.getSimpleName());
        assertThat(schema).as("未生成 OpenAPI schema %s", recordType.getSimpleName()).isNotNull();
        Map<String, io.swagger.v3.oas.models.media.Schema> properties = schema.getProperties();
        String[] componentNames = Arrays.stream(recordType.getRecordComponents())
                .map(component -> component.getName())
                .toArray(String[]::new);
        // 允许额外序列化前端别名（如 SkuDetail.picUrl），但 record 组件本身必须全部出现在 OpenAPI 中。
        assertThat(properties.keySet()).contains(componentNames);
        properties.forEach((name, property) -> {
            String description = property.getDescription();
            if ((description == null || description.isBlank()) && property.get$ref() != null) {
                String referencedSchemaName = property.get$ref()
                        .substring(property.get$ref().lastIndexOf('/') + 1);
                io.swagger.v3.oas.models.media.Schema<?> referencedSchema = schemas.get(referencedSchemaName);
                description = referencedSchema == null ? null : referencedSchema.getDescription();
            }
            assertThat(description)
                    .as("OpenAPI schema %s.%s 缺少字段说明", recordType.getSimpleName(), name)
                    .isNotBlank();
        });
    }
}
