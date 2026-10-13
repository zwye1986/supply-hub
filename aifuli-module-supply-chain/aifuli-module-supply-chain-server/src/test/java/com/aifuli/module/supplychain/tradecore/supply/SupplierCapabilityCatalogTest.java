package com.aifuli.module.supplychain.tradecore.supply;

import com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadataRegistry;
import com.aifuli.module.supplychain.supplieradapter.metadata.Capability;
import com.aifuli.module.supplychain.supplieradapter.metadata.QnmGameOrderV1Metadata;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierConfigurationQueryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 能力目录必须同时返回代码注册的编码与中文说明，不依赖数据库或前端字典。
 * 设计见 docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#84-能力子页。
 */
@ExtendWith(MockitoExtension.class)
class SupplierCapabilityCatalogTest {

    @Mock
    private AdapterMetadataRegistry registry;

    @InjectMocks
    private SupplierConfigurationQueryService query;

    @Test
    void everyCapabilityDefinesChineseDescriptionAndKeepsItsEnumJsonCode() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (Capability capability : Capability.values()) {
            assertThat(capability.getDescription()).matches(".*[\\u4e00-\\u9fff].*");
            assertThat(mapper.readTree(mapper.writeValueAsString(capability)).asText())
                    .isEqualTo(capability.name());
        }
    }

    @Test
    void adapterCatalogSerializesDescriptionsForExactlyItsRegisteredCapabilities() {
        QnmGameOrderV1Metadata metadata = new QnmGameOrderV1Metadata();
        when(registry.all()).thenReturn(List.of(metadata));

        JsonNode adapter = new ObjectMapper().valueToTree(query.listAdapterTypes()).get(0);
        assertThat(adapter.path("adapterType").asText()).isEqualTo(metadata.adapterType());
        Set<String> codes = metadata.capabilities().stream().map(Enum::name).collect(Collectors.toSet());
        assertThat(StreamSupport.stream(adapter.path("capabilities").spliterator(), false)
                .map(JsonNode::asText).collect(Collectors.toSet())).isEqualTo(codes);
        JsonNode descriptions = adapter.path("capabilityDescriptions");
        assertThat(descriptions.isObject()).isTrue();
        assertThat(descriptions.size()).isEqualTo(codes.size());
        codes.forEach(code -> assertThat(descriptions.path(code).asText()).isNotBlank());
        assertThat(descriptions.path("QUERY_ORDER").asText())
                .isEqualTo("查询订单：查询供应商订单状态与履约结果");
        assertThat(descriptions.has("VOID_ASSET")).isFalse();
    }
}
