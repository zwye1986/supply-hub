package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.module.supplychain.tradecore.supply.application.SupplierConfigurationQueryService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierConfigurationQueryService.CredentialItemView;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierConfigurationQueryService.CredentialSetView;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierConfigurationQueryService.CredentialSlotView;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierCooperativeProductService.OfferListItem;
import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 供应商履约技术配置（A1）与合作商品上架即发布（B1）的管理端 REST 契约。
 *
 * <p>锁定设计 {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#11-测试与门禁}
 * 要求的 {@code *ContractTest} 面：T1–T6 只读 GET 与新写端点的路径/方法契约（§6.1/§6.2/§6.3/§6.5）、
 * B1 上架端点路径（§6.6）、{@code OfferListItem} 四个新字段序列化（§6.7 m1+i-b），以及 F1-hybrid
 * 凭据投影「任何响应不含 {@code secret_ref}/密文/明文」的安全契约（§6.3，只写不回显）。</p>
 *
 * <p>权限契约（每个映射挂 {@code @PreAuthorize}、技术菜单 H2 全 {@code supply-chain:admin}）由
 * {@code SupplyChainAdminPermissionContractTest} 与 {@code SupplyChainModuleArchitectureTest} 覆盖，本类不重复。</p>
 */
class SupplierConfigurationAdminRestContractTest {

    /** T1–T6 只读 GET 面：路径与方法必须与设计 §6.1 表逐条一致，防路径漂移。 */
    @Test
    void technicalMenuReadOnlyGetEndpointsMatchDesign() {
        assertRootMapping(SupplierConfigurationAdminController.class, "/supply-chain/supplier-config");
        assertGet(SupplierConfigurationAdminController.class, "listAdapterTypes", "/adapter-types");
        assertGet(SupplierConfigurationAdminController.class, "listConnectors", "/{supplierCode}/connectors");
        assertGet(SupplierConfigurationAdminController.class, "getConnectorVersionDetail",
                "/connectors/{connectorNo}/versions/{versionId}");
        assertGet(SupplierConfigurationAdminController.class, "listCredentialSets", "/{supplierCode}/credential-sets");
        assertGet(SupplierConfigurationAdminController.class, "listCredentialSlots", "/credential-slots");
        assertGet(SupplierConfigurationAdminController.class, "getMappings",
                "/connectors/versions/{versionId}/mappings");
    }

    /** 连接器生命周期 + 凭据写 + 映射写端点：方法/路径与设计 §6.2/§6.3/§6.5 一致（退役用 PUT，其余写用 POST）。 */
    @Test
    void technicalMenuWriteEndpointsMatchDesign() {
        assertPost(SupplierConfigurationAdminController.class, "createConnectorDraft", "/connectors/drafts");
        assertPost(SupplierConfigurationAdminController.class, "validateConnector", "/connectors/validate");
        assertPost(SupplierConfigurationAdminController.class, "publishConnector", "/connectors/publish");
        assertPut(SupplierConfigurationAdminController.class, "retireConnector", "/connectors/retire");
        assertPost(SupplierConfigurationAdminController.class, "createCredentialSet", "/credential-sets");
        assertPost(SupplierConfigurationAdminController.class, "createCredentialItem", "/credential-items");
        assertPost(SupplierConfigurationAdminController.class, "initializeMappings",
                "/connectors/mappings/initialize");
        assertPost(SupplierConfigurationAdminController.class, "addErrorMapping", "/connectors/error-mappings");
        assertPost(SupplierConfigurationAdminController.class, "addStatusMapping", "/connectors/status-mappings");
    }

    /** B1 上架即发布端点：POST /suppliers/{supplierCode}/offers/{offerNo}/list-on-shelf（设计 §6.6）。 */
    @Test
    void listOnShelfEndpointMatchesDesign() {
        assertRootMapping(SupplierAdminController.class, "/supply-chain/suppliers");
        assertPost(SupplierAdminController.class, "listOfferOnShelf",
                "/{supplierCode}/offers/{offerNo}/list-on-shelf");
    }

    /** {@code OfferListItem} 必须暴露 m1+i-b 四个新字段，且各带与设计 §6.7 语义一致的 {@code @Schema}。 */
    @Test
    void offerListItemExposesFulfillmentProjectionFields() {
        assertThat(recordComponentNames(OfferListItem.class))
                .contains("externalSkuCode", "currentVersionId", "offerVersion", "fulfillmentStatus");
        assertThat(schemaDescription(OfferListItem.class, "externalSkuCode")).contains("external_sku_code");
        assertThat(schemaDescription(OfferListItem.class, "currentVersionId")).contains("履约版本");
        assertThat(schemaDescription(OfferListItem.class, "offerVersion")).contains("expectedOfferVersion");
        assertThat(schemaDescription(OfferListItem.class, "fulfillmentStatus"))
                .contains("NOT_CONFIGURED").contains("STALE").contains("READY");
    }

    /**
     * F1-hybrid 安全契约：凭据投影 VO 只回 {@code maskedHint}，绝不含 {@code secretRef}/{@code secretValue}/
     * 密文/明文/{@code activeSecretVersion} 字段，保证任何 GET/列表响应序列化结果永不泄漏凭据（设计 §6.3）。
     */
    @Test
    void credentialProjectionsNeverExposeSecrets() {
        List<String> forbidden = List.of(
                "secretRef", "secretValue", "secretKey", "ciphertext", "plaintext",
                "activeSecretVersion", "envelope", "dataKey");
        for (Class<?> view : List.of(CredentialSlotView.class, CredentialItemView.class, CredentialSetView.class)) {
            assertThat(recordComponentNames(view))
                    .as("%s 不得暴露任何凭据秘密字段", view.getSimpleName())
                    .doesNotContainAnyElementsOf(forbidden);
        }
        assertThat(recordComponentNames(CredentialSlotView.class)).contains("maskedHint");
        assertThat(recordComponentNames(CredentialItemView.class)).contains("maskedHint");
        // 凭据集只聚合非秘密的项投影，本身不携带任何秘密字段。
        assertThat(recordComponentNames(CredentialSetView.class)).contains("setCode", "items");
    }

    /** T1 适配器类型视图须暴露能力与凭据键（供 E1 能力勾选、凭据行数、映射初始化的封闭集来源，设计 §6.1/§6.4）。 */
    @Test
    void adapterTypeViewExposesCapabilitiesAndCredentialKeys() {
        assertThat(recordComponentNames(SupplierConfigurationQueryService.AdapterTypeView.class))
                .contains("adapterType", "capabilities", "capabilityDescriptions", "credentialKeys");
    }

    @Test
    void explicitBindingRequestAndSafeDetailMatchContract() {
        assertThat(Arrays.stream(SupplierConfigurationAdminController.CreateConnectorDraftReqVO.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)).contains("credentialSetCode", "extraConfig");
        assertThat(recordComponentNames(SupplierConfigurationQueryService.AdapterTypeView.class)).contains("credentialBindingMode");
        assertThat(recordComponentNames(SupplierConfigurationQueryService.ConnectorVersionDetail.class))
                .contains("credentialBindingMode", "credentialSlotNo", "credentialSetCode").doesNotContain("secretRef", "secretValue", "credentialItemSnapshot");
    }

    private static void assertRootMapping(Class<?> controller, String expectedRoot) {
        RequestMapping root = controller.getAnnotation(RequestMapping.class);
        assertThat(root).as("%s 必须有类级 @RequestMapping", controller.getSimpleName()).isNotNull();
        assertThat(root.value()).containsExactly(expectedRoot);
    }

    private static void assertGet(Class<?> controller, String methodName, String expectedPath) {
        assertMapping(controller, methodName, GetMapping.class, expectedPath);
    }

    private static void assertPost(Class<?> controller, String methodName, String expectedPath) {
        assertMapping(controller, methodName, PostMapping.class, expectedPath);
    }

    private static void assertPut(Class<?> controller, String methodName, String expectedPath) {
        assertMapping(controller, methodName, PutMapping.class, expectedPath);
    }

    private static void assertMapping(Class<?> controller, String methodName,
                                      Class<? extends Annotation> mappingAnnotation, String expectedPath) {
        Method method = Arrays.stream(controller.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        controller.getSimpleName() + " 缺少方法 " + methodName));
        Annotation annotation = method.getAnnotation(mappingAnnotation);
        assertThat(annotation)
                .as("%s#%s 必须挂 @%s", controller.getSimpleName(), methodName, mappingAnnotation.getSimpleName())
                .isNotNull();
        assertThat(mappingValue(annotation))
                .as("%s#%s 路径", controller.getSimpleName(), methodName)
                .containsExactly(expectedPath);
    }

    private static String[] mappingValue(Annotation annotation) {
        if (annotation instanceof GetMapping get) {
            return get.value();
        }
        if (annotation instanceof PostMapping post) {
            return post.value();
        }
        if (annotation instanceof PutMapping put) {
            return put.value();
        }
        throw new AssertionError("unsupported mapping annotation: " + annotation.annotationType());
    }

    private static List<String> recordComponentNames(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    private static String schemaDescription(Class<?> recordType, String componentName) {
        RecordComponent component = Arrays.stream(recordType.getRecordComponents())
                .filter(candidate -> candidate.getName().equals(componentName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        recordType.getSimpleName() + " 缺少记录分量 " + componentName));
        Schema schema = component.getAccessor().getAnnotation(Schema.class);
        assertThat(schema)
                .as("%s.%s 必须带 @Schema", recordType.getSimpleName(), componentName)
                .isNotNull();
        return schema.description();
    }
}
