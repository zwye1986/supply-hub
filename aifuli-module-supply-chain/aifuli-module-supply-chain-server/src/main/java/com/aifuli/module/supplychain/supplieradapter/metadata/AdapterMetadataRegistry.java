package com.aifuli.module.supplychain.supplieradapter.metadata;

import org.springframework.stereotype.Component;

import java.util.Collection;
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

    /**
     * 枚举全部代码注册的适配器元数据（启动期封闭集，运行期不可变）。
     *
     * <p>技术菜单 T1 适配器类型目录据此渲染，前端只能从该封闭集选择 adapterType，
     * 不能自由声明。设计见
     * docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#61-技术菜单只读-get-面supplierconfigurationadmincontroller当前零-get。</p>
     */
    public Collection<AdapterMetadata> all() {
        return metadataByType.values();
    }
}
