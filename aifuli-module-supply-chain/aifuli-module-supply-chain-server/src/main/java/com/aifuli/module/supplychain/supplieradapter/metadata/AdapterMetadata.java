package com.aifuli.module.supplychain.supplieradapter.metadata;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Set;

/**
 * 代码内封闭的供应商协议元数据。数据库只能引用 adapterType，不能声明实现类或脚本。
 */
public interface AdapterMetadata {
    String adapterType();

    /** 旧单槽适配器兼容默认；集模式必须显式声明，不能推断或混用主键。 */
    default CredentialBindingMode credentialBindingMode() { return CredentialBindingMode.SINGLE_SLOT; }

    /**
     * 新单步骤冻结路径的显式选择；默认不启用，不按无fulfillmentSteps推断或改变旧QNM/华昇。
     * 设计：docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#31-单步骤检查点v1。
     */
    default com.aifuli.module.supplychain.border.fulfillment.domain.singlestep.SingleStepCheckpoint.RecoveryContract singleStepRecoveryContract() {
        return null;
    }

    /** 非秘密扩展参数的封闭 Schema，默认沿用连接器 Schema。 */
    default String extraConfigSchemaResource() { return connectorSchemaResource(); }


    Set<Capability> capabilities();

    String connectorSchemaResource();

    String offerSchemaResource();

    int connectorSchemaVersion();

    int offerSchemaVersion();

    void validateConnectorConfig(int schemaVersion, JsonNode config);

    void validateOfferConfig(int schemaVersion, JsonNode config);

    /** 特定协议的商户身份约束，旧适配器不增加限制；见华昇生产闭环设计§3.2。 */
    default void validateMerchantAccount(String merchantAccount) {}

    /** 有限Schema以外的显式范围约束，不能假设Schema支持minimum/maxLength；见华昇生产闭环设计§3.2。 */
    default void validateExtraConfig(JsonNode config) {}

    /**
     * 该适配器默认的封闭执行画像，服务端在上架发布时注入 offer 版本，前端只读（d2）。
     * 返回组合须自洽并通过 {@code requireExecutionCapabilities}（所需能力落在 {@link #capabilities()} 内）。
     */
    ExecutionProfileDefaults defaultExecutionProfile();

    /**
     * 该适配器所需的凭据项键，UI 据此渲染凭据表单行数（F1）。
     * QNM=[secretKey]，HUA_SHENG=[appSecret,privateKey,callbackPublicKey]。
     */
    Set<String> credentialKeys();

    /**
     * 该适配器的状态映射与错误码映射模板，UI『从模板初始化』据此生成映射行（G2）。协议知识单一来源。
     */
    MappingTemplates mappingTemplates();

    /**
     * 核对映射的协议内容，由配置验证、发布及已发布解析入口共同调用；不执行资金或重路由动作。
     * 封闭适配器覆盖此方法，生命周期仍由入口校验；默认不增加约束，保留既有可扩展适配器行为。
     * 设计见 docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#22-发布合同。
     */
    default void validateMappings(List<StatusMappingDefinitionDO> statuses, List<SupplierErrorCodeMapDO> errors) {}

    /**
     * 是否允许在草稿上逐条追加状态映射；可扩展适配器默认允许，封闭模板必须显式关闭。
     * 这里只声明协议约束，版本生命周期与 HTTP 权限由各自入口负责。
     * 设计见 docs/superpowers/specs/2026-10-12-huasheng-fulfillment-hardening-design.md#31-后端元数据与查询。
     */
    default boolean statusMappingAppendAllowed() { return true; }

    /** 同上，错误映射的逐条追加能力与状态映射分别声明。 */
    default boolean errorMappingAppendAllowed() { return true; }

    /**
     * 管理端展示的中文协议说明；不将状态映射误解为任意资金动作配置。
     * 设计见 docs/superpowers/specs/2026-10-12-huasheng-fulfillment-hardening-design.md#3-映射管理契约。
     */
    default String mappingDescription() {
        return "草稿版本可从模板初始化，并在封闭事实类型、错误分类和重试一致性约束内追加映射；发布后只读。";
    }

    /**
     * 多步骤履约编排声明（HUA_SHENG 文 AD-1 使用）；单步适配器继承此默认空列表。
     */
    default List<String> fulfillmentSteps() {
        return List.of();
    }
}
