package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.module.supplychain.tradecore.catalog.application.CategoryQueryService;
import com.aifuli.module.supplychain.tradecore.catalog.application.CategoryQueryService.CategoryDetail;
import com.aifuli.module.supplychain.tradecore.catalog.application.CategoryQueryService.ManagementCategoryNode;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 类目管理端 OpenAPI 契约：必须有 Tag/Operation，保留集合 GET 并仅新增 management-tree。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#36-openapiswagger}。
 */
class CategoryAdminContractTest {

    @Test
    void documentsTreeCollectionAndHasNoInvoiceOrTreePath() {
        assertThat(CategoryAdminController.class.getAnnotation(Tag.class)).isNotNull();
        assertThat(CategoryAdminController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/supply-chain/categories");
        for (Method method : CategoryAdminController.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            RequestMapping mapping = method.getAnnotation(RequestMapping.class);
            GetMappingAlias paths = GetMappingAlias.from(method);
            if (paths != null) {
                assertThat(method.getAnnotation(Operation.class)).isNotNull();
                assertThat(paths.paths()).noneMatch(path -> path.contains("invoice") || path.equals("/tree"));
            } else if (mapping != null) {
                assertThat(method.getAnnotation(Operation.class)).isNotNull();
                assertThat(Arrays.asList(mapping.value()))
                        .noneMatch(path -> path.contains("invoice") || path.equals("/tree"));
            }
        }
    }

    /** 分类详情只能返回稳定只读 DTO，且所有 Path 参数及 DTO 字段都有 OpenAPI 语义。 */
    @Test
    @SuppressWarnings("rawtypes")
    void categoryDetailUsesDocumentedReadModel() throws NoSuchMethodException {
        Method detail = CategoryAdminController.class.getMethod("detail", String.class);
        assertThat(detail.getGenericReturnType().getTypeName())
                .contains(CategoryDetail.class.getName())
                .doesNotContain("ProductCategoryDO");

        assertThat(Arrays.stream(CategoryDetail.class.getRecordComponents()).map(component -> component.getName()))
                .containsExactly("categoryCode", "categoryNodeNo", "categoryName", "productType", "typeCodes", "description", "remark",
                        "parentId", "categoryLevel", "sort", "visible", "status", "createdAt", "updatedAt");
        Map<String, io.swagger.v3.oas.models.media.Schema> schemas =
                ModelConverters.getInstance().readAll(CategoryDetail.class);
        io.swagger.v3.oas.models.media.Schema<?> categorySchema = schemas.get("CategoryDetail");
        assertThat(categorySchema).as("未生成 CategoryDetail OpenAPI schema").isNotNull();
        Map<String, io.swagger.v3.oas.models.media.Schema> properties = categorySchema.getProperties();
        assertThat(properties.keySet()).containsExactlyInAnyOrder(
                "categoryCode", "categoryNodeNo", "categoryName", "productType", "typeCodes", "description", "remark", "parentId",
                "categoryLevel", "sort", "visible", "status", "createdAt", "updatedAt");
        assertThat(properties.get("categoryNodeNo").getDescription())
                .isEqualTo("分类节点内部全局短序号，供直接子分类编码父段引用");
        properties.forEach((name, property) -> {
            assertThat(property.getDescription())
                    .as("OpenAPI schema CategoryDetail.%s 缺少字段说明", name)
                    .isNotBlank();
        });

        Arrays.stream(CategoryAdminController.class.getDeclaredMethods())
                .flatMap(method -> Arrays.stream(method.getParameters()))
                .filter(parameter -> parameter.isAnnotationPresent(PathVariable.class))
                .forEach(parameter -> {
                    Parameter document = parameter.getAnnotation(Parameter.class);
                    assertThat(document).as("分类 Path 参数 %s 缺少 @Parameter", parameter.getName()).isNotNull();
                    assertThat(document.description()).isNotBlank();
                });
    }

    /** 分类保存契约必须接收并约束详情回显所需的商品类型多选/单选与分类描述/备注。 */
    @Test
    void categorySaveRequestDefinesProductTypeAndDescription() throws NoSuchFieldException {
        var productType = CategoryAdminController.CategorySaveReqVO.class.getDeclaredField("productType");
        assertThat(productType.getAnnotation(Size.class).max()).isEqualTo(32);
        assertThat(productType.getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class).description())
                .isEqualTo("商品类型编码，由sc_product_type动态维护");

        var typeCodes = CategoryAdminController.CategorySaveReqVO.class.getDeclaredField("typeCodes");
        assertThat(typeCodes.getAnnotation(Schema.class).description())
                .isEqualTo("商品类型编码列表，由sc_product_type动态维护");

        var description = CategoryAdminController.CategorySaveReqVO.class.getDeclaredField("description");
        assertThat(description.getAnnotation(Size.class).max()).isEqualTo(200);
        assertThat(description.getAnnotation(Schema.class).description())
                .isEqualTo("分类描述");

        var remark = CategoryAdminController.CategorySaveReqVO.class.getDeclaredField("remark");
        assertThat(remark.getAnnotation(Size.class).max()).isEqualTo(200);
        assertThat(remark.getAnnotation(Schema.class).description())
                .isEqualTo("分类描述/备注");
    }

    /**
     * 分类管理树参数、保存请求与响应模型必须明确状态字典。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#53-商品中心数据字典与配置变更}。
     */
    @Test
    @SuppressWarnings("rawtypes")
    void categoryDocumentsStatusDictionary() throws Exception {
        Method managementTreeMethod = Arrays.stream(CategoryAdminController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("managementTree"))
                .findFirst()
                .orElseThrow();
        Map<String, String> paramDescriptions = Arrays.stream(managementTreeMethod.getParameters())
                .filter(p -> p.isAnnotationPresent(Parameter.class))
                .collect(java.util.stream.Collectors.toMap(
                        java.lang.reflect.Parameter::getName,
                        p -> p.getAnnotation(Parameter.class).description()));

        assertThat(paramDescriptions.get("status"))
                .contains("supply_chain_category_status", "0草稿", "10启用", "20禁用");

        var saveStatusField = CategoryAdminController.CategorySaveReqVO.class.getDeclaredField("status");
        assertThat(saveStatusField.getAnnotation(Schema.class).description())
                .contains("supply_chain_category_status");

        Map<String, io.swagger.v3.oas.models.media.Schema> detailSchemas =
                ModelConverters.getInstance().readAll(CategoryDetail.class);
        io.swagger.v3.oas.models.media.Schema<?> detailSchema = detailSchemas.get("CategoryDetail");
        assertThat(detailSchema).isNotNull();
        io.swagger.v3.oas.models.media.Schema<?> detailStatusProperty =
                (io.swagger.v3.oas.models.media.Schema<?>) detailSchema.getProperties().get("status");
        assertThat(detailStatusProperty.getDescription())
                .contains("supply_chain_category_status");

        Map<String, io.swagger.v3.oas.models.media.Schema> managementNodeSchemas =
                ModelConverters.getInstance().readAll(ManagementCategoryNode.class);
        io.swagger.v3.oas.models.media.Schema<?> nodeSchema = managementNodeSchemas.get("ManagementCategoryNode");
        assertThat(nodeSchema).isNotNull();
        io.swagger.v3.oas.models.media.Schema<?> nodeStatusProperty =
                (io.swagger.v3.oas.models.media.Schema<?>) nodeSchema.getProperties().get("status");
        assertThat(nodeStatusProperty.getDescription())
                .contains("supply_chain_category_status");
    }

    /** 管理树节点契约必须包含原型回显所需的时间、商品类型多选/单选、描述/备注与商品计数，且所有字段都有 OpenAPI 语义。 */
    @Test
    @SuppressWarnings("rawtypes")
    void managementCategoryNodeUsesDocumentedReadModel() {
        assertThat(Arrays.stream(ManagementCategoryNode.class.getRecordComponents()).map(component -> component.getName()))
                .containsExactly("id", "categoryCode", "categoryNodeNo", "categoryName", "productType", "typeCodes", "description", "remark",
                        "categoryLevel", "parentId", "sort", "visible", "status", "effectiveVisible", "productCount",
                        "createdAt", "updatedAt", "children");
        Map<String, io.swagger.v3.oas.models.media.Schema> schemas =
                ModelConverters.getInstance().readAll(ManagementCategoryNode.class);
        io.swagger.v3.oas.models.media.Schema<?> nodeSchema = schemas.get("ManagementCategoryNode");
        assertThat(nodeSchema).as("未生成 ManagementCategoryNode OpenAPI schema").isNotNull();
        Map<String, io.swagger.v3.oas.models.media.Schema> properties = nodeSchema.getProperties();
        assertThat(properties.keySet()).containsExactlyInAnyOrder(
                "id", "categoryCode", "categoryNodeNo", "categoryName", "productType", "typeCodes", "description", "remark",
                "categoryLevel", "parentId", "sort", "visible", "status", "effectiveVisible", "productCount",
                "createdAt", "updatedAt", "children");
        assertThat(properties.get("categoryNodeNo").getDescription())
                .isEqualTo("分类节点内部全局短序号，供直接子分类编码父段引用");
        properties.forEach((name, property) -> {
            assertThat(property.getDescription())
                    .as("OpenAPI schema ManagementCategoryNode.%s 缺少字段说明", name)
                    .isNotBlank();
        });
    }

    private record GetMappingAlias(String[] paths) {
        static GetMappingAlias from(Method method) {
            org.springframework.web.bind.annotation.GetMapping get =
                    method.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class);
            if (get == null) {
                return null;
            }
            String[] value = get.value().length > 0 ? get.value() : get.path();
            return new GetMappingAlias(value.length == 0 ? new String[]{""} : value);
        }
    }
}
