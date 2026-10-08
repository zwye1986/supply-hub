package com.aifuli.framework.tracer.config;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.status.Status;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.logstash.logback.appender.LogstashTcpSocketAppender;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.logback.LogbackLoggingSystem;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通过独立 JVM 加载三服务真实 Boot 根配置，隔离全局 MDC/System properties/LoggerContext。
 * 设计：docs/superpowers/specs/2026-10-04-elk-logging-integration-design.md §5.1.1。
 * 无连接仅代表 1500ms 观察窗口；不是全部后台开销为零的证明。
 */
class LogbackElkAppenderConfigurationTest {
    @TempDir
    Path temporary;

    // movie-server 2026-10-08 决定暂不接入，故不在本参数化列表中；接入时另起用例并同步设计文档。
    @ParameterizedTest(name = "{0}/{1}/enabled={2}")
    @CsvSource({"gateway,dev,true", "gateway,dev,false", "gateway,local,false", "gateway,local,true",
            "system,dev,true", "system,dev,false", "system,local,false", "system,local,true",
            "supplychain,dev,true", "supplychain,dev,false", "supplychain,local,false", "supplychain,local,true",
            "infra,dev,true", "infra,dev,false", "infra,local,false", "infra,local,true",
            "member,dev,true", "member,dev,false", "member,local,false", "member,local,true",
            "aifuli,dev,true", "aifuli,dev,false", "aifuli,local,false", "aifuli,local,true",
            "taobaoflash,dev,true", "taobaoflash,dev,false", "taobaoflash,local,false", "taobaoflash,local,true"})
    void shouldLoadCompleteBootConfiguration(String service, String profile, boolean enabled) throws Exception {
        Path repository = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (!Files.exists(repository.resolve("AGENTS.md"))) {
            repository = repository.getParent();
            if (repository == null) {
                throw new IllegalStateException("找不到仓库根 AGENTS.md");
            }
        }
        String module = switch (service) {
            case "gateway" -> "aifuli-gateway";
            case "system" -> "aifuli-module-system/aifuli-module-system-server";
            case "supplychain" -> "aifuli-module-supply-chain/aifuli-module-supply-chain-server";
            case "infra" -> "aifuli-module-infra/aifuli-module-infra-server";
            case "member" -> "aifuli-module-member/aifuli-module-member-server";
            case "aifuli" -> "aifuli-server";
            case "taobaoflash" -> "aifuli-module-supply-chain/aifuli-channel-taobao-flash-server";
            default -> throw new IllegalArgumentException("未知服务标识: " + service);
        };
        Path result = temporary.resolve("result.json");
        Path output = temporary.resolve("child.log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Probe.class.getName(), repository.resolve(module + "/src/main/resources/logback-spring.xml").toString(),
                profile, Boolean.toString(enabled), temporary.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(25, TimeUnit.SECONDS)).as("子进程超时，不是 GREEN").isTrue();
            assertThat(process.exitValue()).withFailMessage(Files.readString(output)).isZero();
            JsonNode actual = new ObjectMapper().readTree(result.toFile());
            Path evidence = Path.of(System.getProperty("user.dir"), "target", "elk-probe-results");
            Files.createDirectories(evidence);
            String caseName = service + "-" + profile + "-" + enabled;
            Files.copy(result, evidence.resolve(caseName + ".json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.copy(output, evidence.resolve(caseName + ".log"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            boolean sends = enabled && profile.equals("dev");
            assertThat(actual.path("connected").asBoolean()).as(actual.toString()).isEqualTo(sends);
            assertThat(actual.path("file").asText()).contains("[probe-trace] [elkProbe][真实日志] event=sample");
            assertThat(actual.path("errors").size()).as(actual.toString()).isZero();
            String statuses = actual.path("statuses").toString();
            if (!enabled) {
                // 检查完整解析状态：无 Appender 定义处理/跳过，不以 root 无引用推出未实例化。
                assertThat(statuses).doesNotContain("LOGSTASH_TCP");
            } else if (profile.equals("local")) {
                assertThat(statuses).contains("LOGSTASH_TCP", "not referenced", "Skipping");
            }
            if (sends) {
                assertThat(actual.path("appendTimeoutMs").asLong()).isZero();
                assertThat(actual.path("ringBufferSize").asInt()).isEqualTo(4096);
                assertThat(actual.path("reconnectionDelayMs").asLong()).isEqualTo(3000);
                JsonNode event = new ObjectMapper().readTree(actual.path("json").asText());
                assertThat(event.path("traceId").asText()).isEqualTo("probe-trace");
                assertThat(event.path("app_name").asText()).isEqualTo("probe-service");
                assertThat(event.path("env").asText()).isEqualTo(profile);
                assertThat(event.path("message").asText()).isEqualTo("[elkProbe][真实日志] event=sample");
            }
            assertThat(actual.path("remainingWorkers").size()).as(actual.toString()).isZero();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    /** 每个组合使用全新 JVM；监听与读取在当前线程完成，不创建测试线程池。 */
    public static class Probe {
        public static void main(String[] args) throws Exception {
            Path directory = Path.of(args[3]);
            Path file = directory.resolve("application.log");
            Map<String, Object> result = new LinkedHashMap<>();
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            LogbackLoggingSystem system = new LogbackLoggingSystem(Probe.class.getClassLoader());
            List<Thread> before = new ArrayList<>(Thread.getAllStackTraces().keySet());
            try (ServerSocket listener = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
                listener.setSoTimeout(1500);
                StandardEnvironment environment = new StandardEnvironment();
                environment.setActiveProfiles(args[1]);
                environment.getPropertySources().addFirst(new MapPropertySource("probe", Map.of(
                        "spring.application.name", "probe-service", "spring.profiles.active", args[1],
                        "aifuli.logging.elk.enabled", args[2], "aifuli.logging.elk.host", "127.0.0.1",
                        "aifuli.logging.elk.port", listener.getLocalPort(),
                        "aifuli.logging.elk.ring-buffer-size", 4096,
                        "aifuli.logging.elk.reconnection-delay", 3000)));
                System.setProperty("LOG_FILE", file.toString());
                system.beforeInitialize();
                try {
                    system.initialize(new LoggingInitializationContext(environment), Path.of(args[0]).toUri().toString(), null);
                    LogstashTcpSocketAppender tcp = (LogstashTcpSocketAppender) context.getLogger("ROOT").getAppender("LOGSTASH_TCP");
                    if (tcp != null) {
                        result.put("appendTimeoutMs", tcp.getAppendTimeout().getMilliseconds());
                        result.put("ringBufferSize", tcp.getRingBufferSize());
                        result.put("reconnectionDelayMs", tcp.getReconnectionDelay().getMilliseconds());
                    }
                    MDC.put("traceId", "probe-trace");
                    LoggerFactory.getLogger(Probe.class).info("[elkProbe][真实日志] event=sample");
                    MDC.clear();
                    try (Socket connection = listener.accept()) {
                        result.put("connected", true);
                        connection.setSoTimeout(3000);
                        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                        result.put("json", reader.readLine());
                    } catch (SocketTimeoutException timeout) {
                        result.put("connected", false);
                    }
                    result.put("statuses", context.getStatusManager().getCopyOfStatusList().stream().map(Status::getMessage).toList());
                    result.put("errors", context.getStatusManager().getCopyOfStatusList().stream()
                            .filter(status -> status.getLevel() == Status.ERROR).map(Status::getMessage).toList());
                } finally {
                    MDC.clear();
                    system.cleanUp();
                    context.stop();
                }
            } finally {
                // 初始化失败同样关闭上下文，避免无引用 Appender/文件异步 worker 遗留。
                context.stop();
            }
            List<String> remaining = new ArrayList<>();
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (!before.contains(thread) && (thread.getName().contains("logback") || thread.getName().contains("logstash"))) {
                    thread.join(3000);
                    if (thread.isAlive()) {
                        remaining.add(thread.getName());
                    }
                }
            }
            result.put("remainingWorkers", remaining);
            result.put("file", Files.readString(file));
            result.put("bootVersion", org.springframework.boot.SpringBootVersion.getVersion());
            result.put("logbackVersion", LoggerContext.class.getPackage().getImplementationVersion());
            Files.writeString(directory.resolve("result.json"), new ObjectMapper().writeValueAsString(result));
        }
    }
}
