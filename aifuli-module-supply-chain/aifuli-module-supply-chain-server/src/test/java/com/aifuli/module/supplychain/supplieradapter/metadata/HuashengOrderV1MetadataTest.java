package com.aifuli.module.supplychain.supplieradapter.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

/** 华昇封闭发布契约；见 docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#32-metadata-与发布约束。 */
class HuashengOrderV1MetadataTest {
    private final HuashengOrderV1Metadata metadata = new HuashengOrderV1Metadata();
    private final ObjectMapper json = new ObjectMapper();
    private final ClosedJsonSchemaValidator schema = new ClosedJsonSchemaValidator(json);

    @Test void credentialCapabilitiesAndProfileAreClosed() {
        assertThat(metadata.credentialBindingMode()).isEqualTo(CredentialBindingMode.CREDENTIAL_SET);
        assertThat(metadata.credentialKeys()).isEqualTo(Set.of("appSecret", "privateKey", "callbackPublicKey"));
        assertThat(metadata.capabilities()).isEqualTo(Set.of(Capability.ISSUE_ASSET, Capability.QUERY_ORDER, Capability.RECEIVE_CALLBACK));
        assertThat(metadata.defaultExecutionProfile()).isEqualTo(new ExecutionProfileDefaults(
                CompletionMode.QUERY_OR_CALLBACK, CreateIdempotencyMode.DEDUP_ONLY, UnknownResultPolicy.QUERY_ORDER,
                PartialDeliveryMode.FORBIDDEN, ResultMultiplicity.SINGLE, "{}"));
        assertThat(metadata.fulfillmentSteps()).containsExactly("PLACE_ORDER", "CONFIRM_ORDER", "TAKE_CARD", "QUERY_ORDER");
    }

    @Test void connectorAndOfferAcceptOnlyEmptyV1Objects() throws Exception {
        metadata.validateConnectorConfig(1, json.readTree("{}"));
        metadata.validateOfferConfig(1, json.readTree("{}"));
        for (String body : List.of("[]", "null", "{\"appId\":\"1234567890123456\"}", "{\"token\":\"secret\"}")) {
            var node = json.readTree(body);
            assertThatThrownBy(() -> schema.validate(metadata.connectorSchemaResource(), node)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> metadata.validateOfferConfig(1, node)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> metadata.validateConnectorConfig(2, json.readTree("{}"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void extraConfigRejectsUnknownSecondIdentityAndOutOfRangeValues() throws Exception {
        metadata.validateExtraConfig(json.readTree("{}"));
        for (String body : List.of("{\"expireTime\":60}", "{\"expireTime\":1800,\"outMobile\":\"13800138000\"}")) {
            var node = json.readTree(body);
            schema.validate(metadata.extraConfigSchemaResource(), node);
            metadata.validateExtraConfig(node);
        }
        for (String body : List.of("{\"expireTime\":59}", "{\"expireTime\":1801}", "{\"expireTime\":60.0}",
                "{\"expireTime\":\"60\"}", "{\"expireTime\":9223372036854775808}", "{\"appId\":\"other\"}",
                "{\"token\":\"secret\"}", "{\"outMobile\":null}", "{\"outMobile\":\" \"}",
                "{\"outMobile\":\"\\n\"}", "{\"outMobile\":\"" + "密".repeat(22) + "\"}")) {
            var node = json.readTree(body);
            assertThatThrownBy(() -> metadata.validateExtraConfig(node)).isInstanceOf(IllegalArgumentException.class);
        }
        metadata.validateMerchantAccount("1234567890123456");
        assertThatThrownBy(() -> metadata.validateMerchantAccount("123456789012345")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metadata.validateMerchantAccount("密".repeat(16))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void allTenMappingsAreNonTerminalAndOnlySuccessWaitAreAccepted() {
        var templates = metadata.mappingTemplates();
        assertThat(templates.statusMappings()).hasSize(10);
        assertThat(templates.errorCodeMappings()).isEmpty();
        for (var row : templates.statusMappings()) {
            assertThat(row.terminal()).isFalse();
            assertThat(row.externalSubStatus()).isEmpty();
            assertThat(row.factType()).isEqualTo(row.externalStatus().endsWith("SUCCESS")
                    || row.externalStatus().endsWith("WAIT") ? "ACCEPTED" : "RESULT_UNKNOWN");
        }
        var rows = templates.statusMappings().stream().map(row -> StatusMappingDefinitionDO.builder()
                .externalStatus(row.externalStatus()).externalSubStatus(row.externalSubStatus()).businessPhase(row.businessPhase())
                .factType(row.factType()).terminalFlag((short) 0).build()).toList();
        metadata.validateMappings(rows, List.of());
        rows.get(0).setTerminalFlag((short) 1);
        assertThatThrownBy(() -> metadata.validateMappings(rows, List.of())).isInstanceOf(IllegalArgumentException.class);
        rows.get(0).setTerminalFlag((short) 0);
        rows.get(1).setFactType("SUCCEEDED");
        assertThatThrownBy(() -> metadata.validateMappings(rows, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metadata.validateMappings(rows.subList(0, 9), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metadata.validateMappings(rows, List.of(SupplierErrorCodeMapDO.builder().actionType("RETRY_SAFE").build())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
