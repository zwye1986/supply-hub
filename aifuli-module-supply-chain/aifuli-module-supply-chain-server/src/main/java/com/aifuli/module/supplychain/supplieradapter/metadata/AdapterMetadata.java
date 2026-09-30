package com.aifuli.module.supplychain.supplieradapter.metadata;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/**
 * 代码内封闭的供应商协议元数据。数据库只能引用 adapterType，不能声明实现类或脚本。
 */
public interface AdapterMetadata {
    String adapterType();

    Set<Capability> capabilities();

    String connectorSchemaResource();

    String offerSchemaResource();

    int connectorSchemaVersion();

    int offerSchemaVersion();

    void validateConnectorConfig(int schemaVersion, JsonNode config);

    void validateOfferConfig(int schemaVersion, JsonNode config);
}
