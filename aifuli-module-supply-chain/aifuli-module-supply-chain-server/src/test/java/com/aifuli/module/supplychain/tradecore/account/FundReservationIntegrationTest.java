package com.aifuli.module.supplychain.tradecore.account;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.tradecore.account.application.BuyerAccountOpeningService;
import com.aifuli.module.supplychain.tradecore.account.application.FundReservationService;
import com.aifuli.module.supplychain.tradecore.account.dal.dataobject.BuyerAccountDO;
import com.aifuli.module.supplychain.tradecore.account.dal.mysql.BuyerAccountMapper;
import com.aifuli.module.supplychain.tradecore.account.domain.Money;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerCommandService;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.ledger.application.AccountLedgerWriter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FundReservationIntegrationTest {

    private static final int REPEAT_ROUNDS = 5;
    private static final int CONCURRENT_FREEZES = 20;
    private static final long OPENING_BALANCE = 1_100L;
    private static final long FREEZE_AMOUNT = 100L;

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain")
            .withUsername("supply_chain")
            .withPassword("supply_chain");

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
    void preservesAccountInvariantsAndExactlyOneTerminalOutcomeUnderConcurrency() throws Exception {
        try (ConfigurableApplicationContext context = applicationContext()) {
            BuyerCommandService buyerService = context.getBean(BuyerCommandService.class);
            BuyerAccountOpeningService openingService = context.getBean(BuyerAccountOpeningService.class);
            FundReservationService reservationService = context.getBean(FundReservationService.class);
            BuyerAccountMapper accountMapper = context.getBean(BuyerAccountMapper.class);
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(
                    "supplyChainDataSource", javax.sql.DataSource.class));
            assertThat(AopUtils.isAopProxy(openingService)).isTrue();
            assertThat(AopUtils.isAopProxy(reservationService)).isTrue();

            for (int round = 0; round < REPEAT_ROUNDS; round++) {
                verifyOneRound(round, buyerService, openingService, reservationService, accountMapper, jdbc);
            }
            verifyZeroBalanceOverdraftAndMissingAccount(
                    context, buyerService, openingService, reservationService, accountMapper);
        }
    }

    private void verifyOneRound(int round,
                                BuyerCommandService buyerService,
                                BuyerAccountOpeningService openingService,
                                FundReservationService reservationService,
                                BuyerAccountMapper accountMapper,
                                JdbcTemplate jdbc) throws Exception {
        String buyerCode = "FUNDS_" + round;
        buyerService.createBuyer(new BuyerCommandService.CreateBuyerCommand(
                buyerCode, "资金并发采购商" + round, "资金采购商" + round,
                null, null, "AIFULI_MALL", "tenant-funds-" + round,
                LocalDateTime.now(ZoneOffset.UTC), 1L));

        BuyerAccountOpeningService.OpeningBalanceCommand openingCommand =
                new BuyerAccountOpeningService.OpeningBalanceCommand(
                        buyerCode, Money.cny(OPENING_BALANCE), 1L,
                        "evidence://opening/" + round, new byte[32], "期初余额集成测试",
                        "trace-opening-" + round, "test-opening-" + round);
        BuyerAccountOpeningService.AccountSnapshot opened = openingService.open(openingCommand);
        assertThat(opened.available()).isEqualTo(OPENING_BALANCE);
        BuyerAccountOpeningService.AccountSnapshot replayedOpening = openingService.open(openingCommand);
        assertThat(replayedOpening.accountNo()).isEqualTo(opened.accountNo());
        assertThat(ledgerCount(jdbc, accountMapper.selectByBuyerId(opened.buyerId()).getId())).isEqualTo(1L);

        ConcurrentResults<FundReservationService.ReservationSnapshot> sameReference = concurrent(
                2, index -> reservationService.freeze(
                        new FundReservationService.FreezeCommand(
                                opened.buyerId(), "CARD_ORDER", "same-reference-" + round,
                                Money.cny(FREEZE_AMOUNT), "B-SAME-" + round,
                                "trace-same-" + round + "-" + index, "test-same-" + index)));
        assertThat(sameReference.failures()).isEmpty();
        assertThat(sameReference.successes()).hasSize(2)
                .extracting(FundReservationService.ReservationSnapshot::reservationNo)
                .containsOnly(sameReference.successes().getFirst().reservationNo());
        assertThat(ledgerCount(jdbc, accountMapper.selectByBuyerId(opened.buyerId()).getId()))
                .isEqualTo(2L);

        ConcurrentResults<FundReservationService.ReservationSnapshot> freezes = concurrent(
                CONCURRENT_FREEZES, index -> reservationService.freeze(
                        new FundReservationService.FreezeCommand(
                                opened.buyerId(), "CARD_ORDER", "freeze-" + round + "-" + index,
                                Money.cny(FREEZE_AMOUNT), "B-ORDER-" + round + "-" + index,
                                "trace-freeze-" + round + "-" + index, "test-freeze-" + index)));
        assertThat(freezes.successes())
                .withFailMessage("Unexpected concurrent freeze failures: %s", describe(freezes.failures()))
                .hasSize(CONCURRENT_FREEZES);
        assertThat(freezes.failures()).isEmpty();

        BuyerAccountDO overdrawn = accountMapper.selectByBuyerId(opened.buyerId());
        long frozenAfterConcurrent = FREEZE_AMOUNT + CONCURRENT_FREEZES * FREEZE_AMOUNT;
        assertAccount(overdrawn, OPENING_BALANCE, frozenAfterConcurrent);
        assertThat(overdrawn.getBalance() - overdrawn.getFrozen()).isNegative();

        FundReservationService.ReservationSnapshot first = freezes.successes().getFirst();
        long ledgerCountBeforeReplay = ledgerCount(jdbc, overdrawn.getId());
        FundReservationService.ReservationSnapshot replay = reservationService.freeze(
                new FundReservationService.FreezeCommand(
                        opened.buyerId(), first.referenceType(), first.referenceNo(),
                        Money.cny(first.totalAmount()), "IGNORED-REPLAY-ORDER",
                        "trace-replay-" + round, "test-replay-" + round));
        assertThat(replay.reservationNo()).isEqualTo(first.reservationNo());
        assertThat(ledgerCount(jdbc, overdrawn.getId())).isEqualTo(ledgerCountBeforeReplay);
        assertThatThrownBy(() -> reservationService.freeze(
                new FundReservationService.FreezeCommand(
                        opened.buyerId(), first.referenceType(), first.referenceNo(),
                        Money.cny(first.totalAmount() + 1), null,
                        "trace-conflict-" + round, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idempotency conflict");

        ConcurrentResults<FundReservationService.ReservationSnapshot> terminals = concurrent(
                List.of(
                        () -> reservationService.commit(first.reservationNo(), "B-ORDER-" + round,
                                "trace-commit-" + round, "test-terminal-" + round),
                        () -> reservationService.release(first.reservationNo(), "B-ORDER-" + round,
                                "trace-release-" + round, "test-terminal-" + round)));
        assertThat(terminals.successes()).hasSize(1);
        assertThat(terminals.failures()).hasSize(1)
                .allSatisfy(failure -> assertThat(failure)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("current status"));

        FundReservationService.ReservationSnapshot terminal = terminals.successes().getFirst();
        assertThat(terminal.status()).isIn((short) 30, (short) 40);
        assertThat(terminal.remainingAmount()).isZero();
        assertThat(terminal.committedAmount() + terminal.releasedAmount())
                .isEqualTo(terminal.totalAmount());

        BuyerAccountDO afterTerminal = accountMapper.selectByBuyerId(opened.buyerId());
        long expectedBalance = terminal.status() == 30
                ? OPENING_BALANCE - FREEZE_AMOUNT : OPENING_BALANCE;
        assertAccount(afterTerminal, expectedBalance, frozenAfterConcurrent - FREEZE_AMOUNT);
        assertThat(ledgerCount(jdbc, afterTerminal.getId())).isEqualTo(23L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_account_ledger WHERE account_id = ? AND entry_key IN (?, ?)",
                Long.class, afterTerminal.getId(), "COMMIT:" + first.reservationNo(),
                "RELEASE:" + first.reservationNo())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_fund_reservation WHERE account_id = ? "
                        + "AND total_amount <> committed_amount + released_amount + remaining_amount",
                Long.class, afterTerminal.getId())).isZero();
    }

    private void verifyZeroBalanceOverdraftAndMissingAccount(
            ConfigurableApplicationContext context,
            BuyerCommandService buyerService,
            BuyerAccountOpeningService openingService,
            FundReservationService reservationService,
            BuyerAccountMapper accountMapper) {
        buyerService.createBuyer(new BuyerCommandService.CreateBuyerCommand(
                "FUNDS_ZERO", "零余额采购商", "零余额采购商",
                null, null, "AIFULI_MALL", "tenant-funds-zero",
                LocalDateTime.now(ZoneOffset.UTC), 1L));
        BuyerAccountOpeningService.AccountSnapshot opened = openingService.open(
                new BuyerAccountOpeningService.OpeningBalanceCommand(
                        "FUNDS_ZERO", Money.cny(0L), 1L,
                        "evidence://opening/zero", new byte[32], "零期初",
                        "trace-opening-zero", "test-opening-zero"));
        assertThat(opened.balance()).isZero();
        assertThat(opened.available()).isZero();

        FundReservationService.ReservationSnapshot frozen = reservationService.freeze(
                new FundReservationService.FreezeCommand(
                        opened.buyerId(), "CARD_ORDER", "zero-overdraft",
                        Money.cny(FREEZE_AMOUNT), "B-ZERO",
                        "trace-zero-freeze", "test-zero-freeze"));
        BuyerAccountDO afterFreeze = accountMapper.selectByBuyerId(opened.buyerId());
        assertAccount(afterFreeze, 0L, FREEZE_AMOUNT);
        assertThat(afterFreeze.getBalance() - afterFreeze.getFrozen()).isEqualTo(-FREEZE_AMOUNT);

        reservationService.commit(frozen.reservationNo(), "B-ZERO",
                "trace-zero-commit", "test-zero-commit");
        BuyerAccountDO afterCommit = accountMapper.selectByBuyerId(opened.buyerId());
        assertAccount(afterCommit, -FREEZE_AMOUNT, 0L);

        buyerService.createBuyer(new BuyerCommandService.CreateBuyerCommand(
                "FUNDS_NOACC", "未开户采购商", "未开户采购商",
                null, null, "AIFULI_MALL", "tenant-funds-noacc",
                LocalDateTime.now(ZoneOffset.UTC), 1L));
        Long missingBuyerId = context.getBean(BuyerMapper.class)
                .selectByBuyerCode("FUNDS_NOACC").getId();
        assertThatThrownBy(() -> reservationService.freeze(
                new FundReservationService.FreezeCommand(
                        missingBuyerId, "CARD_ORDER", "missing-account",
                        Money.cny(FREEZE_AMOUNT), "B-MISSING",
                        "trace-missing", "test-missing")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("account does not exist");
    }

    private static void assertAccount(BuyerAccountDO account, long balance, long frozen) {
        assertThat(account.getBalance()).isEqualTo(balance);
        assertThat(account.getFrozen()).isEqualTo(frozen);
        assertThat(account.getFrozen()).isGreaterThanOrEqualTo(0L);
    }

    private static long ledgerCount(JdbcTemplate jdbc, Long accountId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sc_account_ledger WHERE account_id = ?",
                Long.class, accountId);
    }

    private static List<String> describe(List<Throwable> failures) {
        return failures.stream()
                .map(failure -> failure.getClass().getName() + ": " + failure.getMessage())
                .toList();
    }

    private static <T> ConcurrentResults<T> concurrent(int taskCount, IndexedTask<T> task) throws Exception {
        List<Callable<T>> tasks = new ArrayList<>(taskCount);
        for (int index = 0; index < taskCount; index++) {
            int taskIndex = index;
            tasks.add(() -> task.call(taskIndex));
        }
        return concurrent(tasks);
    }

    private static <T> ConcurrentResults<T> concurrent(List<Callable<T>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<T>> futures = tasks.stream()
                    .map(task -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return task.call();
                    }))
                    .toList();
            ready.await();
            start.countDown();

            List<T> successes = new ArrayList<>();
            List<Throwable> failures = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    successes.add(future.get());
                } catch (ExecutionException exception) {
                    failures.add(exception.getCause());
                }
            }
            return new ConcurrentResults<>(successes, failures);
        }
    }

    private ConfigurableApplicationContext applicationContext() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("aifuli.supply-chain.enabled", true);
        properties.put("aifuli.supply-chain.datasource.url", jdbcUrl());
        properties.put("aifuli.supply-chain.datasource.username", username());
        properties.put("aifuli.supply-chain.datasource.password", password());
        properties.put("aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        return new SpringApplicationBuilder(TestConfiguration.class)
                .web(WebApplicationType.NONE)
                .properties(properties)
                .run();
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
            BuyerCommandService.class, BuyerAccountOpeningService.class,
            FundReservationService.class, AccountLedgerWriter.class})
    static class TestConfiguration {
    }

    @FunctionalInterface
    private interface IndexedTask<T> {
        T call(int index) throws Exception;
    }

    private record ConcurrentResults<T>(List<T> successes, List<Throwable> failures) {
    }
}
