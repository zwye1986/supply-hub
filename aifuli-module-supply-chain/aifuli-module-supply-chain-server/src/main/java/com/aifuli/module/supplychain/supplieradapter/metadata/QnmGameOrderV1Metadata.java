package com.aifuli.module.supplychain.supplieradapter.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/** 奇那梦 gameOrder V1 协议的封闭能力与配置 Schema。 */
@Component
public final class QnmGameOrderV1Metadata implements AdapterMetadata {

    public static final String TYPE = "QNM_GAME_ORDER_V1";

    @Override
    public String adapterType() {
        return TYPE;
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.ISSUE_ASSET, Capability.CREATE_RECHARGE,
                Capability.QUERY_ORDER, Capability.RECEIVE_CALLBACK, Capability.QUERY_BALANCE);
    }

    @Override
    public ExecutionProfileDefaults defaultExecutionProfile() {
        // 自洽组合：QUERY_OR_CALLBACK 要求 QUERY_ORDER + RECEIVE_CALLBACK，均在 capabilities() 内；
        // DEDUP_ONLY 与 QUERY_ORDER 不触发 REPLAY_SAME_KEY→REPLAYABLE_RESULT 约束；配置须空对象（validateOfferConfig）。
        return new ExecutionProfileDefaults(
                CompletionMode.QUERY_OR_CALLBACK,
                CreateIdempotencyMode.DEDUP_ONLY,
                UnknownResultPolicy.QUERY_ORDER,
                PartialDeliveryMode.FORBIDDEN,
                ResultMultiplicity.SINGLE,
                "{}");
    }

    @Override
    public Set<String> credentialKeys() {
        // QNM 一期仅需商户密钥；UI 据此渲染单行凭据表单（F1）。
        return Set.of("secretKey");
    }

    @Override
    public MappingTemplates mappingTemplates() {
        // 迁移自原 SupplierConfigurationCommandService.initializeQnmStatusMappings 的 QNM 一期封闭状态映射，
        // 列表顺序即初始化优先级；错误码映射留空，由运营按需追加（G2）。
        List<MappingTemplates.StatusMappingTemplate> statusMappings = List.of(
                new MappingTemplates.StatusMappingTemplate("ResCode=1", "", "CREATE", "ACCEPTED", false),
                new MappingTemplates.StatusMappingTemplate("ResCode=OK", "", "CREATE", "ACCEPTED", false),
                new MappingTemplates.StatusMappingTemplate("orderStatus=0", "", "QUERY", "ACCEPTED", false),
                new MappingTemplates.StatusMappingTemplate("orderStatus=1", "", "QUERY", "SUCCEEDED", true),
                new MappingTemplates.StatusMappingTemplate("orderStatus=2", "", "QUERY", "REJECTED", true),
                new MappingTemplates.StatusMappingTemplate("status=1", "", "CALLBACK", "SUCCEEDED", true),
                new MappingTemplates.StatusMappingTemplate("status=2", "", "CALLBACK", "REJECTED", true));
        return new MappingTemplates(statusMappings, List.of());
    }

    @Override
    public String connectorSchemaResource() {
        return "supply-chain/adapter-schemas/qnm-game-order-v1-connector.schema.json";
    }

    @Override
    public String offerSchemaResource() {
        return "supply-chain/adapter-schemas/qnm-game-order-v1-offer.schema.json";
    }

    @Override
    public int connectorSchemaVersion() {
        return 1;
    }

    @Override
    public int offerSchemaVersion() {
        return 1;
    }

    @Override
    public void validateConnectorConfig(int schemaVersion, JsonNode config) {
        requireClosedEmptyObject(schemaVersion, connectorSchemaVersion(), config, "connector");
    }

    @Override
    public void validateOfferConfig(int schemaVersion, JsonNode config) {
        requireClosedEmptyObject(schemaVersion, offerSchemaVersion(), config, "offer");
    }

    private static void requireClosedEmptyObject(int actualVersion, int expectedVersion,
                                                 JsonNode config, String configType) {
        if (actualVersion != expectedVersion) {
            throw new IllegalArgumentException("Unknown QNM " + configType + " Schema version: " + actualVersion);
        }
        if (config == null || !config.isObject() || !config.isEmpty()) {
            throw new IllegalArgumentException("QNM " + configType + " config must be an empty JSON object");
        }
    }
}
