package com.aifuli.module.supplychain.infrastructure.configuration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 明文回显变更集可能在测试库已用另一个 id 执行过同一 SQL。
 * 设计见 {@code docs/superpowers/specs/2026-09-03-supply-chain-admin-rbac-design.md#83-旧-admin-兼容角色与脚本}。
 */
class SupplierAdminPlaintextEchoChangesetTest {

    @Test
    void marksPlaintextEchoRanWhenLegalPersonIdNoAlreadyExists() throws Exception {
        Path changelog = findChangelog();
        String yaml = Files.readString(changelog, StandardCharsets.UTF_8);
        String changeset = Arrays.stream(yaml.split("(?m)^  - changeSet:\\n"))
                .filter(block -> block.contains("021-supplier-admin-plaintext-echo.sql"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing plaintext echo changeset"));
        assertThat(changeset).contains("onFail: MARK_RAN");
        assertThat(changeset).contains("columnExists:");
        assertThat(changeset).contains("tableName: sc_supplier");
        assertThat(changeset).contains("columnName: legal_person_id_no");
    }

    private static Path findChangelog() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(
                    "src/main/resources/db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml");
            if (Files.exists(candidate)) {
                return candidate;
            }
            candidate = current.resolve(
                    "aifuli-module-supply-chain/aifuli-module-supply-chain-server"
                            + "/src/main/resources/db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml");
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到 db.changelog-1.0.0.yaml");
    }
}
