package com.aifuli.module.supplychain.supplieradapter.metadata;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** adapterType 到唯一代码实现的启动期封闭注册表。 */
@Component
public class AdapterMetadataRegistry {

    private final Map<String, AdapterMetadata> metadataByType;

    public AdapterMetadataRegistry(List<AdapterMetadata> metadataList) {
        Map<String, AdapterMetadata> registered = new LinkedHashMap<>();
        for (AdapterMetadata metadata : metadataList) {
            AdapterMetadata existing = registered.putIfAbsent(metadata.adapterType(), metadata);
            if (existing != null) {
                throw new IllegalStateException("Duplicate adapter metadata: " + metadata.adapterType());
            }
        }
        this.metadataByType = Map.copyOf(registered);
    }

    public AdapterMetadata require(String adapterType) {
        AdapterMetadata metadata = metadataByType.get(adapterType);
        if (metadata == null) {
            throw new IllegalArgumentException("Unsupported adapter type: " + adapterType);
        }
        return metadata;
    }
}
