package com.aifuli.module.supplychain.infrastructure;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.inbox.InboxService;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxDeliveryClaimService;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxDeliveryEngine;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxDispatchHandler;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxDispatchHandlerRegistry;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventDO;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.infrastructure.timer.DatabasePersistentTimer;
import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimer;
import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimerHandler;
import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimerHandlerRegistry;
import com.aifuli.module.supplychain.infrastructure.timer.TimerClaimService;
import com.aifuli.module.supplychain.infrastructure.timer.TimerEngineLifecycle;
import com.aifuli.module.supplychain.infrastructure.timer.TimerTaskContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class TimerOutboxInboxIntegrationTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain").withUsername("supply_chain").withPassword("supply_chain");
    private static final String EXTERNAL_JDBC_URL = System.getProperty("supply-chain.test.jdbc-url");

    @BeforeAll
    static void startContainer() {
        if (EXTERNAL_JDBC_URL == null) {
            MYSQL.start();
        }
    }

    @AfterAll
    static void stopContainer() {
        if (EXTERNAL_JDBC_URL == null && MYSQL.isRunning()) {
            MYSQL.stop();
        }
    }

    @Test
    void timerExecutesRetriesAndDeadLettersAndOutboxDeliversAndInboxDedups() throws Exception {
        try (ConfigurableApplicationContext context = applicationContext()) {
            PersistentTimer timer = context.getBean(PersistentTimer.class);
            OutboxEventRepository outbox = context.getBean(OutboxEventRepository.class);
            InboxService inbox = context.getBean(InboxService.class);
            RecordingTimerHandler recorder = context.getBean(RecordingTimerHandler.class);
            FlakyTimerHandler flaky = context.getBean(FlakyTimerHandler.class);
            RecordingDispatchHandler dispatch = context.getBean(RecordingDispatchHandler.class);
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));

            ObjectNode payload = context.getBean(ObjectMapper.class).createObjectNode();
            payload.put("key", "T-1");
            timer.scheduleOnce(RecordingTimerHandler.TYPE, "T-1", payload, Instant.now());
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(recorder.count.get()).isEqualTo(1));
            await().untilAsserted(() -> assertThat(taskStatus(jdbc, RecordingTimerHandler.TYPE, "T-1"))
                    .isEqualTo((short) 40));
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sc_timer_execution WHERE task_id IN "
                            + "(SELECT id FROM sc_timer_task WHERE task_type=? AND idempotency_key=? AND status=40)",
                    Long.class, RecordingTimerHandler.TYPE, "T-1")).isEqualTo(1L);
            assertThat(jdbc.queryForObject(
                    "SELECT attempt_no FROM sc_timer_execution WHERE task_id IN "
                            + "(SELECT id FROM sc_timer_task WHERE task_type=? AND idempotency_key=?)",
                    Integer.class, RecordingTimerHandler.TYPE, "T-1")).isEqualTo(1);

            timer.scheduleOnce(FlakyTimerHandler.TYPE, "F-1", payload, Instant.now());
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                assertThat(flaky.count.get()).isEqualTo(3);
                assertThat(taskStatus(jdbc, FlakyTimerHandler.TYPE, "F-1")).isEqualTo((short) 60);
                assertThat(jdbc.queryForList(
                        "SELECT attempt_no FROM sc_timer_execution WHERE task_id IN "
                                + "(SELECT id FROM sc_timer_task WHERE task_type=? AND idempotency_key=?) "
                                + "ORDER BY attempt_no",
                        Integer.class, FlakyTimerHandler.TYPE, "F-1"))
                        .containsExactly(1, 2, 3);
            });

            outbox.appendLocal("TEST_AGG", "AGG-1", 0, "TEST_EVENT", RecordingDispatchHandler.TARGET,
                    Map.of("hello", "world"), "trace-1", null, null,
                    java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(dispatch.count.get()).isEqualTo(1));
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM sc_outbox_event WHERE event_type='TEST_EVENT'", Short.class))
                    .isEqualTo((short) 40);

            byte[] eventBytes = "payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            InboxService.InboxAttempt first = inbox.tryStart("test-consumer", "EVT-1", eventBytes, "trace-1");
            assertThat(first.shouldProcess()).isTrue();
            inbox.succeed(first.inboxId(), 0);
            InboxService.InboxAttempt second = inbox.tryStart("test-consumer", "EVT-1", eventBytes, "trace-1");
            assertThat(second.shouldProcess()).isFalse();
        }
    }

    private short taskStatus(JdbcTemplate jdbc, String type, String key) {
        return jdbc.queryForObject(
                "SELECT status FROM sc_timer_task WHERE task_type=? AND idempotency_key=?",
                Short.class, type, key);
    }

    private ConfigurableApplicationContext applicationContext() {
        java.util.Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("aifuli.supply-chain.enabled", true);
        properties.put("aifuli.supply-chain.datasource.url", jdbcUrl());
        properties.put("aifuli.supply-chain.datasource.username", username());
        properties.put("aifuli.supply-chain.datasource.password", password());
        properties.put("aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        properties.put("aifuli.supply-chain.timer.scan-interval", "300ms");
        properties.put("aifuli.supply-chain.timer.batch-size", 16);
        properties.put("aifuli.supply-chain.timer.lease-duration", "15s");
        properties.put("aifuli.supply-chain.timer.renew-interval", "5s");
        properties.put("aifuli.supply-chain.timer.worker-pool-size", 4);
        properties.put("aifuli.supply-chain.timer.backoff-base", "200ms");
        properties.put("aifuli.supply-chain.timer.backoff-max", "1s");
        properties.put("aifuli.supply-chain.outbox.scan-interval", "300ms");
        properties.put("aifuli.supply-chain.outbox.batch-size", 16);
        properties.put("aifuli.supply-chain.outbox.lease-duration", "15s");
        properties.put("aifuli.supply-chain.outbox.renew-interval", "5s");
        properties.put("aifuli.supply-chain.outbox.worker-pool-size", 4);
        properties.put("aifuli.supply-chain.outbox.backoff-base", "200ms");
        properties.put("aifuli.supply-chain.outbox.backoff-max", "1s");
        String[] arguments = properties.entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
        return new SpringApplicationBuilder(TestConfiguration.class)
                .web(WebApplicationType.NONE).run(arguments);
    }

    private static String jdbcUrl() {
        return EXTERNAL_JDBC_URL != null ? EXTERNAL_JDBC_URL : MYSQL.getJdbcUrl();
    }

    private static String username() {
        return EXTERNAL_JDBC_URL != null
                ? System.getProperty("supply-chain.test.jdbc-username", "root") : MYSQL.getUsername();
    }

    private static String password() {
        return EXTERNAL_JDBC_URL != null
                ? System.getProperty("supply-chain.test.jdbc-password", "") : MYSQL.getPassword();
    }

    @Configuration(proxyBeanMethods = false)
    @Import({SupplyChainDataSourceConfiguration.class, UlidGenerator.class, BusinessNoGenerator.class,
            TimerEngineLifecycle.class, TimerClaimService.class, DatabasePersistentTimer.class,
            PersistentTimerHandlerRegistry.class, OutboxDeliveryEngine.class,
            OutboxDeliveryClaimService.class, OutboxDispatchHandlerRegistry.class,
            OutboxEventRepository.class, InboxService.class})
    static class TestConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        RecordingTimerHandler recordingTimerHandler() {
            return new RecordingTimerHandler();
        }

        @Bean
        FlakyTimerHandler flakyTimerHandler() {
            return new FlakyTimerHandler();
        }

        @Bean
        RecordingDispatchHandler recordingDispatchHandler() {
            return new RecordingDispatchHandler();
        }
    }

    static final class RecordingTimerHandler implements PersistentTimerHandler {
        static final String TYPE = "RECORDING_TIMER";
        final AtomicInteger count = new AtomicInteger();

        @Override
        public String taskType() {
            return TYPE;
        }

        @Override
        public String handle(TimerTaskContext context, JsonNode payload) {
            count.incrementAndGet();
            return "OK";
        }
    }

    static final class FlakyTimerHandler implements PersistentTimerHandler {
        static final String TYPE = "FLAKY_TIMER";
        final AtomicInteger count = new AtomicInteger();

        @Override
        public String taskType() {
            return TYPE;
        }

        @Override
        public int maxAttempts() {
            return 3;
        }

        @Override
        public String handle(TimerTaskContext context, JsonNode payload) {
            count.incrementAndGet();
            throw new IllegalStateException("always fails until dead-letter");
        }
    }

    static final class RecordingDispatchHandler implements OutboxDispatchHandler {
        static final String TARGET = "test-dispatch";
        final AtomicInteger count = new AtomicInteger();

        @Override
        public String targetName() {
            return TARGET;
        }

        @Override
        public void dispatch(OutboxEventDO event) {
            count.incrementAndGet();
        }
    }
}
