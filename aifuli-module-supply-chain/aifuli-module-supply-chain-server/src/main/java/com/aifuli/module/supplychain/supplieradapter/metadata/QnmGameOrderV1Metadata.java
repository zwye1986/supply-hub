package com.aifuli.module.supplychain.supplieradapter.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

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
