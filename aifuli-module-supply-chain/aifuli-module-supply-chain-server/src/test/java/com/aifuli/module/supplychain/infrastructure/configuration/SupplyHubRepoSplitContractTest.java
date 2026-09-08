package com.aifuli.module.supplychain.infrastructure.configuration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * supply-hub 裁剪门禁。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-supply-hub-repo-split-design.md#5-测试与发版门禁}。
 */
class SupplyHubRepoSplitContractTest {

    @Test
    void payErpMallModuleDirectoriesAreGone() {
        assertThat(repoDir("aifuli-module-pay")).doesNotExist();
        assertThat(repoDir("aifuli-module-erp")).doesNotExist();
        assertThat(repoDir("aifuli-module-mall")).doesNotExist();
    }

    @Test
    void rootPomDoesNotDeclarePayErpMall() throws IOException {
        String pom = Files.readString(repoFile("pom.xml"));
        assertThat(pom).doesNotContain("<module>aifuli-module-pay</module>");
        assertThat(pom).doesNotContain("<module>aifuli-module-erp</module>");
        assertThat(pom).doesNotContain("<module>aifuli-module-mall</module>");
        assertThat(pom).contains("<module>aifuli-module-member</module>");
        assertThat(pom).contains("<module>aifuli-module-supply-chain</module>");
    }

    @Test
    void gatewayYamlOmitsMallPayErpStatistics() throws IOException {
        String yaml = Files.readString(repoFile("aifuli-gateway/src/main/resources/application.yaml"));
        assertThat(yaml).doesNotContain("uri: grayLb://pay-server");
        assertThat(yaml).doesNotContain("uri: grayLb://mall-server");
        assertThat(yaml).doesNotContain("uri: grayLb://erp-server");
        assertThat(yaml).doesNotContain("uri: grayLb://statistics-server");
        assertThat(yaml).doesNotContain("service-name: pay-server");
        assertThat(yaml).doesNotContain("service-name: mall-server");
        assertThat(yaml).doesNotContain("service-name: erp-server");
        assertThat(yaml).doesNotContain("service-name: statistics-server");
        assertThat(yaml).contains("id: supply-chain-admin-api");
        assertThat(yaml).contains("${AIFULI_GATEWAY_SUPPLY_CHAIN_URI:grayLb://supply-chain-server}");
    }

    @Test
    void memberDoesNotDependOnTradeApi() throws IOException {
        String pom = Files.readString(repoFile(
                "aifuli-module-member/aifuli-module-member-server/pom.xml"));
        String rpc = Files.readString(repoFile(
                "aifuli-module-member/aifuli-module-member-server/src/main/java/"
                        + "com/aifuli/module/member/framework/rpc/config/RpcConfiguration.java"));
        assertThat(pom).doesNotContain("aifuli-module-trade-api");
        assertThat(rpc).doesNotContain("com.aifuli.module.trade");
        assertThat(rpc).doesNotContain("TradePlatform");
        assertThat(repoFile(
                "aifuli-module-member/aifuli-module-member-server/src/main/java/"
                        + "com/aifuli/module/member/controller/app/open/OpenMallMemberController.java"))
                .doesNotExist();
        assertThat(repoFile(
                "aifuli-module-member/aifuli-module-member-api/src/main/java/"
                        + "com/aifuli/module/member/api/profile/MemberProfileResolveApi.java"))
                .exists();
        assertThat(repoFile(
                "aifuli-module-member/aifuli-module-member-api/src/main/java/"
                        + "com/aifuli/module/member/api/sso/MemberSsoTokenApi.java"))
                .exists();
    }

    private static Path repoFile(String relative) {
        return resolve(relative);
    }

    private static Path repoDir(String relative) {
        return resolve(relative);
    }

    /**
     * 相对仓库根解析路径。Maven 在子模块目录跑测时当前目录也有 {@code pom.xml}，
     * 不能把「相对路径存在」当成已到达仓库根。
     * 设计见 {@code docs/superpowers/specs/2026-09-07-supply-hub-repo-split-design.md#5-测试与发版门禁}。
     */
    private static Path resolve(String relative) {
        return repoRoot().resolve(relative);
    }

    /**
     * 向上查找同时包含网关与供应链模块的目录，与 {@link SupplyhubDeployContractTest} 一样从 cwd 走祖先。
     * 找不到时退回 cwd，使「不应存在」的路径 {@code exists()} 为 false。
     */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8; i++) {
            if (Files.isDirectory(dir.resolve("aifuli-gateway"))
                    && Files.isDirectory(dir.resolve("aifuli-module-supply-chain"))
                    && Files.isRegularFile(dir.resolve("pom.xml"))) {
                return dir;
            }
            Path parent = dir.getParent();
            if (parent == null) {
                break;
            }
            dir = parent;
        }
        return Path.of("").toAbsolutePath();
    }
}
