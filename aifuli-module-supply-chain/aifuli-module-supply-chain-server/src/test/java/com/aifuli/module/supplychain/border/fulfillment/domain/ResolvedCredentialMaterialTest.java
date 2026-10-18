package com.aifuli.module.supplychain.border.fulfillment.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class ResolvedCredentialMaterialTest {
    @Test
    void consumptionCopiesAreIsolatedAndErasedOnSuccessAndFailureAndCloseErasesOwnedArrays() {
        char[] merchant = "synthetic-merchant".toCharArray();
        char[] privateKey = "synthetic-private".toCharArray();
        Map<String, char[]> input = new LinkedHashMap<>(Map.of("merchantKey", merchant, "privateKey", privateKey));
        var material = ResolvedCredentialMaterial.takeOwnership(Set.of("merchantKey", "privateKey"), input);
        input.clear();
        var temporary = new AtomicReference<char[]>();
        String consumed = material.use("merchantKey", chars -> {
            temporary.set(chars);
            String result = new String(chars);
            chars[0] = 'X';
            return result;
        });
        assertThat(consumed).isEqualTo("synthetic-merchant");
        assertThat(temporary.get()).containsOnly('\0');
        assertThat(merchant).isEqualTo("synthetic-merchant".toCharArray());
        var failure = new IllegalStateException("synthetic consumer failure");
        assertThatThrownBy(() -> material.use("privateKey", chars -> {
            temporary.set(chars);
            throw failure;
        })).isSameAs(failure);
        assertThat(temporary.get()).containsOnly('\0');
        assertThat(privateKey).isEqualTo("synthetic-private".toCharArray());
        assertThatThrownBy(() -> material.use("appSecret", String::new)).isInstanceOf(IllegalArgumentException.class);
        material.close();
        material.close();
        assertThat(merchant).containsOnly('\0');
        assertThat(privateKey).containsOnly('\0');
        assertThatThrownBy(() -> material.use("merchantKey", String::new)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMissingExtraOrEmptyKeysAndErasesAllTransferredArrays() {
        for (String invalid : Set.of("missing", "extra", "empty")) {
            Map<String, char[]> values = new LinkedHashMap<>();
            values.put("merchantKey", "synthetic-merchant".toCharArray());
            if (!invalid.equals("missing")) values.put("privateKey", invalid.equals("empty") ? new char[0] : "synthetic-private".toCharArray());
            if (invalid.equals("extra")) values.put("undeclaredKey", "synthetic-extra".toCharArray());
            assertThatThrownBy(() -> ResolvedCredentialMaterial.takeOwnership(Set.of("merchantKey", "privateKey"), values))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("synthetic-");
            values.values().forEach(chars -> assertThat(chars).isEqualTo(new char[chars.length]));
        }
    }

    @Test
    void materialCannotBeSerializedOrRenderedAsPlaintext() {
        try (var material = ResolvedCredentialMaterial.takeOwnership(Set.of("secretKey"),
                Map.of("secretKey", "synthetic-sensitive".toCharArray()))) {
            assertThat(material.toString()).doesNotContain("synthetic-sensitive");
            assertThatThrownBy(() -> new ObjectMapper().writeValueAsString(material))
                    .hasMessageContaining("禁止序列化").hasMessageNotContaining("synthetic-sensitive");
        }
    }
}
