package com.aifuli.module.supplychain.infrastructure.configuration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * supplyhub Compose 与网关供应链静态路由契约。
 *
 * <p>设计见 {@code docs/superpowers/specs/2026-09-04-supplyhub-test-docker-compose-design.md#12-测试门禁仓库}。</p>
 */
class SupplyhubDeployContractTest {

    @Test
    void gatewayYamlRoutesSupplyChainWithGrayLbDefault() throws IOException {
        String yaml = Files.readString(repoFile("aifuli-gateway/src/main/resources/application.yaml"));
        assertThat(yaml).contains("id: supply-chain-admin-api");
        assertThat(yaml).contains("id: supply-chain-app-api");
        assertThat(yaml).contains("${AIFULI_GATEWAY_SUPPLY_CHAIN_URI:grayLb://supply-chain-server}");
        assertThat(yaml).contains("Path=/admin-api/supply-chain/**");
        assertThat(yaml).contains("name: supply-chain-server");
        assertThat(yaml).contains("url: /admin-api/supply-chain/v3/api-docs");
    }

    @Test
    void composeDefinesFourHostNetworkServicesAndOmitsSupplyChain() throws IOException {
        String compose = Files.readString(repoFile("script/deploy/supplyhub-test/docker-compose.yml"));
        assertThat(compose).contains("network_mode: host");
        assertThat(compose).contains("container_name: supplyhub-gateway");
        assertThat(compose).contains("container_name: supplyhub-system");
        assertThat(compose).contains("container_name: supplyhub-infra");
        assertThat(compose).contains("container_name: supplyhub-member");
        // 2026-09-07：pay/erp/mall/statistics 已移除，断言其不存在，防止误加回。
        assertThat(compose).doesNotContain("container_name: supplyhub-pay");
        assertThat(compose).doesNotContain("container_name: supplyhub-erp");
        assertThat(compose).doesNotContain("container_name: supplyhub-mall");
        assertThat(compose).doesNotContain("container_name: supplyhub-statistics");
        assertThat(compose).doesNotContain("container_name: supply-chain-server");
        assertThat(compose).doesNotContain("58190");
        assertThat(compose).contains("SERVER_PORT: \"59080\"");
        assertThat(compose).contains("SERVER_PORT: \"59081\"");
        assertThat(compose).contains("SERVER_PORT: \"59087\"");
        assertThat(compose).doesNotContain("68080");
        assertThat(listedTcpPorts(compose)).isNotEmpty();
        assertThat(listedTcpPorts(compose)).allMatch(port -> port >= 1 && port <= 65535);
    }

    @Test
    void nginxProxiesGatewayOnValidHostPortWithoutUpstream() throws IOException {
        String conf = Files.readString(repoFile("script/deploy/supplyhub-test/nginx/supplyhubtest.qihaofu.cn.conf"));
        assertThat(conf).doesNotContain("\nupstream ");
        assertThat(conf).doesNotMatch("(?m)^upstream\\s");
        assertThat(conf).contains("proxy_pass http://172.19.64.105:59080");
        assertThat(conf).doesNotContain("68080");
        assertThat(listedTcpPorts(conf)).allMatch(port -> port >= 1 && port <= 65535);
    }

    private static Path repoFile(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            Path parent = dir.getParent();
            if (parent == null) {
                break;
            }
            dir = parent;
        }
        throw new AssertionError("未找到仓库文件: " + relative);
    }

    /** 抽取 Compose / Nginx 里声明的监听或反代端口，用于断言不超过 TCP 上限 65535。 */
    private static List<Integer> listedTcpPorts(String text) {
        Pattern pattern = Pattern.compile(
                "(?:SERVER_PORT: \"|DISCOVERY_PORT: \"|/dev/tcp/127\\.0\\.0\\.1/|172\\.19\\.64\\.105:|listen )(\\d+)");
        Matcher matcher = pattern.matcher(text);
        List<Integer> ports = new ArrayList<>();
        while (matcher.find()) {
            ports.add(Integer.parseInt(matcher.group(1)));
        }
        return ports;
    }
}
