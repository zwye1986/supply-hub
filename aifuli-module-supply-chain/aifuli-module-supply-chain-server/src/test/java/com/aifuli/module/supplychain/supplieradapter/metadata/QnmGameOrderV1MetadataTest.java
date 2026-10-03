package com.aifuli.module.supplychain.supplieradapter.metadata;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QNM 适配器元数据扩展契约：默认执行画像、凭据键、映射模板与履约步骤。
 *
 * <p>这些值是协议知识的单一来源，前端下拉/凭据行数/映射初始化（T1 GET）与后端发布注入（d2）
 * 均据此派生，消除前后端漂移。设计见
 * {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#51-adaptermetadata-扩展}。</p>
 */
class QnmGameOrderV1MetadataTest {

    private final QnmGameOrderV1Metadata metadata = new QnmGameOrderV1Metadata();

    /** QNM 一期仅需一项商户密钥，UI 据此渲染单行凭据表单（F1）。 */
    @Test
    void declaresSingleSecretKeyCredential() {
        assertThat(metadata.credentialKeys()).containsExactly("secretKey");
    }

    /**
     * 默认执行画像须自洽：completionMode=QUERY_OR_CALLBACK 同时要求 QUERY_ORDER 与 RECEIVE_CALLBACK 能力，
     * QNM capabilities() 两者皆备，故能通过 {@code requireExecutionCapabilities}（d2 服务端注入、前端只读）。
     */
    @Test
    void defaultExecutionProfileIsSelfConsistent() {
        assertThat(metadata.defaultExecutionProfile()).isEqualTo(new ExecutionProfileDefaults(
                CompletionMode.QUERY_OR_CALLBACK,
                CreateIdempotencyMode.DEDUP_ONLY,
                UnknownResultPolicy.QUERY_ORDER,
                PartialDeliveryMode.FORBIDDEN,
                ResultMultiplicity.SINGLE,
                "{}"));
    }

    /** 默认执行画像的能力要求必须落在 QNM 封闭能力集内，否则发布期能力校验会误拒。 */
    @Test
    void defaultExecutionProfileCapabilitiesAreSubsetOfAdapterCapabilities() {
        ExecutionProfileDefaults profile = metadata.defaultExecutionProfile();
        Set<Capability> capabilities = metadata.capabilities();
        if (profile.completionMode() == CompletionMode.QUERY
                || profile.completionMode() == CompletionMode.QUERY_OR_CALLBACK
                || profile.unknownResultPolicy() == UnknownResultPolicy.QUERY_ORDER) {
            assertThat(capabilities).contains(Capability.QUERY_ORDER);
        }
        if (profile.completionMode() == CompletionMode.CALLBACK
                || profile.completionMode() == CompletionMode.QUERY_OR_CALLBACK
                || profile.unknownResultPolicy() == UnknownResultPolicy.WAIT_CALLBACK) {
            assertThat(capabilities).contains(Capability.RECEIVE_CALLBACK);
        }
    }

    /**
     * 映射模板迁移自现 {@code initializeQnmStatusMappings} 的 QNM 一期封闭映射（7 条状态映射，
     * 顺序即优先级），错误码映射留空由运营按需追加（G2 从模板初始化并支持追加）。
     */
    @Test
    void mappingTemplatesCarryQnmPhaseOneStatusSeeds() {
        MappingTemplates templates = metadata.mappingTemplates();
        assertThat(templates.statusMappings()).containsExactly(
                new MappingTemplates.StatusMappingTemplate("ResCode=1", "", "CREATE", "ACCEPTED", false),
                new MappingTemplates.StatusMappingTemplate("ResCode=OK", "", "CREATE", "ACCEPTED", false),
                new MappingTemplates.StatusMappingTemplate("orderStatus=0", "", "QUERY", "ACCEPTED", false),
                new MappingTemplates.StatusMappingTemplate("orderStatus=1", "", "QUERY", "SUCCEEDED", true),
                new MappingTemplates.StatusMappingTemplate("orderStatus=2", "", "QUERY", "REJECTED", true),
                new MappingTemplates.StatusMappingTemplate("status=1", "", "CALLBACK", "SUCCEEDED", true),
                new MappingTemplates.StatusMappingTemplate("status=2", "", "CALLBACK", "REJECTED", true));
        assertThat(templates.errorCodeMappings()).isEmpty();
    }

    /** QNM 为单步履约，继承接口默认空步骤（多步编排属 HUA_SHENG AD-1）。 */
    @Test
    void singleStepAdapterHasNoFulfillmentSteps() {
        assertThat(metadata.fulfillmentSteps()).isEqualTo(List.of());
    }
}
