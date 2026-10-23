package com.aifuli.module.supplychain.tradecore.buyer;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.security.AesGcmEnvelopeCrypto;
import com.aifuli.module.supplychain.tradecore.account.application.BuyerAccountOpeningService;
import com.aifuli.module.supplychain.tradecore.account.application.BuyerAccountAdjustmentService;
import com.aifuli.module.supplychain.tradecore.account.application.FundReservationService;
import com.aifuli.module.supplychain.tradecore.account.dal.dataobject.BuyerAccountDO;
import com.aifuli.module.supplychain.tradecore.account.dal.mysql.BuyerAccountMapper;
import com.aifuli.module.supplychain.tradecore.account.domain.Money;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerAdminCommandService;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerAdminCommandService.ContactLine;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerAdminCommandService.CreateBuyerCommand;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerAdminCommandService.UpdateBuyerCommand;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerAdminQueryService;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerAdminQueryService.BuyerChildItem;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerInvoiceService;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerInvoiceService.BuyerInvoiceView;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerInvoiceService.SaveInvoiceCommand;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerInvoiceDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerInvoiceMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProjectStatus;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerStatus;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerProductCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerProductCommandService.ProductLine;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerProductQueryService;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerProductQueryService.BuyerProductListItem;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.application.SettlementPriceResolver;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerProductL2OverrideDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerProductL2OverrideVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerProductSettlementPriceDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerProductSettlementPriceVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSettlementPriceDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSettlementPriceVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerProductL2OverrideMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerProductL2OverrideVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerProductSettlementPriceMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerProductSettlementPriceVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.FulfillmentType;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.TaxMetadata;
import com.aifuli.module.supplychain.integration.system.TaxRateDictionaryAdapter;
import com.aifuli.module.supplychain.tradecore.ledger.application.AccountLedgerWriter;
import com.aifuli.module.supplychain.tradecore.profile.domain.L2AdjustMode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import com.aifuli.module.supplychain.tradecore.ledger.dal.dataobject.AccountLedgerDO;
import com.aifuli.module.supplychain.tradecore.ledger.dal.mysql.AccountLedgerMapper;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import com.aifuli.module.supplychain.testsupport.ProductCenterTestConfiguration;
import org.testcontainers.containers.MySQLContainer;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_PARENT_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 分销商管理端建档期初、组织树，以及分销商品手工 L2 覆盖；历史模板价预留行不得参与解析。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#61-组织树}
 * 与 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#63-分销商品}。
 */
class BuyerAdminIntegrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain")
            .withUsername("supply_chain")
            .withPassword("supply_chain");

    private static final long L1_CENTS = 800L;
    private static final long LEGACY_TEMPLATE_CENTS = 650L;
    private static final int CUSTOM_PERCENT = 110;
    private static final long CUSTOM_RESOLVED_CENTS = 880L;
    private static final String EXTERNAL_JDBC_URL = System.getProperty("supply-chain.test.jdbc-url");
    private static final String KEY_BASE64 = Base64.getEncoder().encodeToString(new byte[32]);

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
    void invoicePrefillsWithoutInsertThenPersonalSaveClearsBankAndDoesNotWriteBack() {
        try (ConfigurableApplicationContext context = applicationContext()) {
            BuyerAdminCommandService command = context.getBean(BuyerAdminCommandService.class);
            BuyerAdminQueryService query = context.getBean(BuyerAdminQueryService.class);
            BuyerInvoiceService invoices = context.getBean(BuyerInvoiceService.class);
            BuyerInvoiceMapper invoiceMapper = context.getBean(BuyerInvoiceMapper.class);
            BuyerMapper buyerMapper = context.getBean(BuyerMapper.class);

            String buyerCode = command.create(createCommand("开票一级", "开票科技有限公司",
                    "91110000000000021X", 1, null, 0L));
            Long buyerId = buyerMapper.selectByBuyerCode(buyerCode).getId();

            BuyerInvoiceView prefilled = invoices.get(buyerCode);
            assertThat(prefilled.persisted()).isFalse();
            assertThat(prefilled.id()).isNull();
            assertThat(prefilled.titleType()).isEqualTo("ENTERPRISE");
            assertThat(prefilled.invoiceTitle()).isEqualTo("开票科技有限公司");
            assertThat(prefilled.socialCreditCode()).isEqualTo("91110000000000021X");
            assertThat(prefilled.phone()).isEqualTo("13800138000");
            assertThat(prefilled.bankName()).isEqualTo("测试银行");
            assertThat(prefilled.bankAccount()).isEqualTo("6222021234567890");
            assertThat(invoiceMapper.selectCount(new LambdaQueryWrapper<BuyerInvoiceDO>()
                    .eq(BuyerInvoiceDO::getBuyerId, buyerId))).isZero();

            BuyerInvoiceView enterprise = invoices.save(buyerCode, new SaveInvoiceCommand(
                    "ENTERPRISE", "独立发票抬头", "91110000MA1K3ABC0X", "13900000000",
                    "招商银行", "6225880000000001", "浦东"), 8L);
            assertThat(enterprise.persisted()).isTrue();
            assertThat(invoiceMapper.selectByBuyerId(buyerId).getBankAccount()).isEqualTo("6225880000000001");

            command.update(updateCommand(buyerCode, "档案改名", "档案新公司", "91110000000000021X",
                    1, null, null, "档案新银行"));
            var afterProfile = query.get(buyerCode);
            assertThat(afterProfile.buyerName()).isEqualTo("档案改名");
            assertThat(afterProfile.legalEntityName()).isEqualTo("档案新公司");
            assertThat(afterProfile.bankName()).isEqualTo("档案新银行");

            BuyerInvoiceView afterProfileChange = invoices.get(buyerCode);
            assertThat(afterProfileChange.persisted()).isTrue();
            assertThat(afterProfileChange.invoiceTitle()).isEqualTo("独立发票抬头");
            assertThat(afterProfileChange.bankName()).isEqualTo("招商银行");
            assertThat(afterProfileChange.bankAccount()).isEqualTo("6225880000000001");

            BuyerInvoiceView personal = invoices.save(buyerCode, new SaveInvoiceCommand(
                    "PERSONAL", "张三", "91110000MA1K3ABC0X", "13800000000",
                    "工商银行", "6222021001001234567", "地址"), 8L);
            assertThat(personal.titleType()).isEqualTo("PERSONAL");
            assertThat(personal.socialCreditCode()).isNull();
            assertThat(personal.bankName()).isNull();
            assertThat(personal.bankAccount()).isNull();
            BuyerInvoiceDO row = invoiceMapper.selectByBuyerId(buyerId);
            assertThat(row.getBankAccount()).isNull();
            assertThat(row.getSocialCreditCode()).isNull();
            assertThat(query.get(buyerCode).bankName()).isEqualTo("档案新银行");
        }
    }

    @Test
    void createsOpeningOnceAndAllowsSameBalanceAndParentRules() {
        try (ConfigurableApplicationContext context = applicationContext()) {
            BuyerAdminCommandService command = context.getBean(BuyerAdminCommandService.class);
            BuyerAdminQueryService query = context.getBean(BuyerAdminQueryService.class);
            BuyerMapper buyerMapper = context.getBean(BuyerMapper.class);
            BuyerAccountMapper accountMapper = context.getBean(BuyerAccountMapper.class);

            String l1 = command.create(levelOne("一级", 0L));
            assertThat(l1).startsWith("BYR_");
            BuyerAccountDO account = accountMapper.selectByBuyerId(buyerMapper.selectByBuyerCode(l1).getId());
            assertThat(account.getBalance()).isZero();
            command.update(updateWithOpening(l1, 0L));
            assertThat(accountMapper.selectByBuyerId(account.getBuyerId()).getVersion()).isEqualTo(account.getVersion());

            assertThatThrownBy(() -> command.create(levelTwoWithoutParent()))
                    .isInstanceOf(ServiceException.class)
                    .extracting(ex -> ((ServiceException) ex).getCode())
                    .isEqualTo(BUYER_PARENT_INVALID.getCode());

            String l2 = command.create(levelTwo("二级", l1));
            assertThat(query.children(l1)).extracting(BuyerChildItem::buyerCode).containsExactly(l2);
            assertThatThrownBy(() -> command.update(changeLevelToTwoButKeepChild(l1)))
                    .isInstanceOf(ServiceException.class)
                    .extracting(ex -> ((ServiceException) ex).getCode())
                    .isEqualTo(BUYER_PARENT_INVALID.getCode());

            command.changeStatus(l1, BuyerStatus.BLACKLIST, 1L);
            command.changeStatus(l1, BuyerStatus.ACTIVE, 1L);

            addsProductAndIgnoresLegacyTemplatePrice(context, l1);
        }
    }

    private static final AtomicInteger BALANCE_BUYER_SEQUENCE = new AtomicInteger(300);

    /** 真实账户和账本验证，设计见 2026-10-23-buyer-admin-balance-adjustment-design.md §6。 */
    @ParameterizedTest
    @CsvSource({"1500,500", "600,-400", "0,-1000"})
    void adjustsBalanceAndAppendsAuditedLedger(long target, long delta) {
        try (ConfigurableApplicationContext context = applicationContext()) {
            String code = createBalanceBuyer(context);
            BuyerAccountDO before = balanceAccount(context, code);
            // 通过真实资金服务创建冻结与账本，验证归零不触碰冻结、不强加 balance >= frozen。
            context.getBean(FundReservationService.class)
                    .freeze(new FundReservationService.FreezeCommand(
                            before.getBuyerId(), "CARD_ORDER", "balance-test-" + code,
                            Money.cny(800L), null, "freeze-balance-test", null));
            long version = balanceAccount(context, code).getVersion();
            context.getBean(BuyerAdminCommandService.class).update(balanceUpdate(context, code, target));
            BuyerAccountDO after = balanceAccount(context, code);
            assertThat(after.getBalance()).isEqualTo(target);
            assertThat(after.getFrozen()).isEqualTo(800L);
            assertThat(after.getVersion()).isEqualTo(version + 1);
            assertThat(after.getUpdatedBy()).isEqualTo(1L);
            var entries = balanceLedgers(context, after.getId());
            assertThat(entries).hasSize(3);
            AccountLedgerDO entry = entries.getLast();
            assertThat(entry.getEntryType()).isEqualTo("ADJUSTMENT");
            assertThat(entry.getBalanceBefore()).isEqualTo(1000L);
            assertThat(entry.getBalanceAfter()).isEqualTo(target);
            assertThat(entry.getBalanceDelta()).isEqualTo(delta);
            assertThat(entry.getFrozenBefore()).isEqualTo(800L);
            assertThat(entry.getFrozenAfter()).isEqualTo(800L);
            assertThat(entry.getFrozenDelta()).isZero();
            assertThat(entry.getOperatorId()).isEqualTo(1L);
            assertThat(entry.getTraceId()).isEqualTo("buyer-admin-update-test");
            assertThat(entry.getReferenceType()).isEqualTo("ADMIN_BALANCE_ADJUSTMENT");
            assertThat(entry.getEntryKey()).isEqualTo("ADMIN_BALANCE_ADJUSTMENT:" + entry.getReferenceNo());
            assertThat(entry.getCausationId()).isEqualTo(entry.getReferenceNo());
            assertThat(entry.getEvidenceRef()).contains(entry.getReferenceNo());
            assertThat(entry.getEvidenceDigest()).hasSize(32);
            assertThat(entry.getAuditNote()).isEqualTo("管理端分销商余额修正");
            assertThat(entry.getOccurredAt()).isEqualTo(after.getUpdatedAt());
            assertThat(context.getBean(BuyerAdminQueryService.class).get(code).accountBalanceCents()).isEqualTo(target);
        }
    }

    @ParameterizedTest
    @CsvSource(value = {"1000", "NULL"}, nullValues = "NULL")
    void sameOrAbsentBalanceDoesNotWriteAccountOrLedger(Long target) {
        try (ConfigurableApplicationContext context = applicationContext()) {
            String code = createBalanceBuyer(context);
            BuyerAccountDO before = balanceAccount(context, code);
            context.getBean(BuyerAdminCommandService.class).update(balanceUpdate(context, code, target));
            assertThat(balanceAccount(context, code)).isEqualTo(before);
            assertThat(balanceLedgers(context, before.getId())).hasSize(1);
            assertThat(context.getBean(BuyerAdminQueryService.class).get(code).buyerName()).isEqualTo("一级");
        }
    }

    @Test
    void ledgerFailureRollsBackProfileContactsAccountAndInsertedLedger() {
        try (ConfigurableApplicationContext context = applicationContext()) {
            String code = createBalanceBuyer(context);
            BuyerAdminCommandService commands = context.getBean(BuyerAdminCommandService.class);
            BuyerAdminQueryService queries = context.getBean(BuyerAdminQueryService.class);
            var profileBefore = queries.get(code);
            BuyerAccountDO before = balanceAccount(context, code);
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", javax.sql.DataSource.class));
            var contactsBefore = jdbc.queryForList("SELECT * FROM sc_buyer_contact WHERE buyer_id = ? ORDER BY id", before.getBuyerId());
            var ledgersBefore = balanceLedgers(context, before.getId());
            context.getBean(AdjustmentLedgerFailureInjector.class).fail = true;
            assertThatThrownBy(() -> commands.update(balanceUpdate(context, code, 2000L)))
                    .isInstanceOf(IllegalStateException.class).hasMessage("injected after adjustment ledger insert");
            assertThat(queries.get(code)).isEqualTo(profileBefore);
            assertThat(jdbc.queryForList("SELECT * FROM sc_buyer_contact WHERE buyer_id = ? ORDER BY id", before.getBuyerId()))
                    .isEqualTo(contactsBefore);
            assertThat(balanceAccount(context, code)).isEqualTo(before);
            assertThat(balanceLedgers(context, before.getId())).isEqualTo(ledgersBefore);
        }
    }

    @Test
    void adjustmentServiceRequiresOuterSupplyChainTransaction() {
        try (ConfigurableApplicationContext context = applicationContext()) {
            String code = createBalanceBuyer(context);
            BuyerAccountDO before = balanceAccount(context, code);
            assertThatThrownBy(() -> context.getBean(BuyerAccountAdjustmentService.class).adjust(
                    new BuyerAccountAdjustmentService.AdjustmentCommand(before.getBuyerId(), 0, 1L, "trace-test")))
                    .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
            assertThat(balanceAccount(context, code)).isEqualTo(before);
            assertThat(balanceLedgers(context, before.getId())).hasSize(1);
        }
    }

    @Test
    void concurrentDifferentTargetsProduceContinuousLedgerChain() throws Exception {
        try (ConfigurableApplicationContext context = applicationContext(); var pool = Executors.newFixedThreadPool(2)) {
            String code = createBalanceBuyer(context);
            var commands = context.getBean(BuyerAdminCommandService.class);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (long target : new long[]{1500L, 2000L}) {
                tasks.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                    commands.update(balanceUpdate(context, code, target));
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var task : tasks) task.get(20, TimeUnit.SECONDS);
            BuyerAccountDO after = balanceAccount(context, code);
            var entries = balanceLedgers(context, after.getId());
            assertThat(entries).hasSize(3);
            assertThat(entries.get(1).getBalanceBefore()).isEqualTo(1000L);
            assertThat(entries.get(2).getBalanceBefore()).isEqualTo(entries.get(1).getBalanceAfter());
            assertThat(entries.get(2).getBalanceAfter()).isEqualTo(after.getBalance());
            assertThat(entries.get(1).getEntryKey()).isNotEqualTo(entries.get(2).getEntryKey());
            assertThat(after.getVersion()).isEqualTo(2L);
        }
    }

    private static String createBalanceBuyer(ConfigurableApplicationContext context) {
        int seq = BALANCE_BUYER_SEQUENCE.incrementAndGet();
        return context.getBean(BuyerAdminCommandService.class).create(createCommand(
                "余额测试" + seq, "余额测试有限公司" + seq, String.format("911100000000%06d", seq), 1, null, 1000L));
    }

    private static UpdateBuyerCommand balanceUpdate(ConfigurableApplicationContext context, String code, Long target) {
        BuyerDO buyer = context.getBean(BuyerMapper.class).selectByBuyerCode(code);
        return updateCommand(code, "一级", buyer.getLegalEntityName(), buyer.getCreditCode(), 1, null, target);
    }

    private static BuyerAccountDO balanceAccount(ConfigurableApplicationContext context, String code) {
        return context.getBean(BuyerAccountMapper.class).selectByBuyerId(
                context.getBean(BuyerMapper.class).selectByBuyerCode(code).getId());
    }

    private static List<AccountLedgerDO> balanceLedgers(ConfigurableApplicationContext context, Long accountId) {
        return context.getBean(AccountLedgerMapper.class).selectList(new LambdaQueryWrapper<AccountLedgerDO>()
                .eq(AccountLedgerDO::getAccountId, accountId).orderByAsc(AccountLedgerDO::getId));
    }

    /** 仅测试使用：在真实账本 INSERT 后注入异常，验证外层事务没有提前提交或独立提交。 */
    static class AdjustmentLedgerFailureInjector implements BeanPostProcessor {
        volatile boolean fail;

        @Override
        public Object postProcessAfterInitialization(Object bean, String name) {
            if (bean instanceof AccountLedgerWriter writer) {
                AccountLedgerWriter spy = org.mockito.Mockito.spy(writer);
                org.mockito.Mockito.doAnswer(invocation -> {
                    invocation.callRealMethod();
                    AccountLedgerWriter.LedgerEntry entry = invocation.getArgument(0);
                    if (fail && "ADJUSTMENT".equals(entry.entryType())) {
                        throw new IllegalStateException("injected after adjustment ledger insert");
                    }
                    return null;
                }).when(spy).append(any());
                return spy;
            }
            return bean;
        }
    }

    /**
     * 锁定分销层级页的完整后端契约：返回全部后代、穿透归档中间节点、批量聚合字段并按创建时间排序。
     * 设计见
     * {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#61-组织树}
     * 与
     * {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#62-列表与档案}。
     */
    @Test
    void listsAllVisibleDescendantsWithAggregatesInCreationOrder() {
        try (ConfigurableApplicationContext context = applicationContext()) {
            BuyerAdminCommandService command = context.getBean(BuyerAdminCommandService.class);
            BuyerAdminQueryService query = context.getBean(BuyerAdminQueryService.class);
            BuyerMapper buyerMapper = context.getBean(BuyerMapper.class);
            BuyerProjectMapper projectMapper = context.getBean(BuyerProjectMapper.class);

            String root = command.create(createCommand(
                    "层级根节点", "层级根节点有限公司", "91110000000000101X", 1, null, 0L));
            String archivedMiddle = command.create(createCommand(
                    "归档中间节点", "归档中间节点有限公司", "91110000000000102X",
                    2, root, 0L));
            String grandchild = command.create(createCommand(
                    "可见孙级", "可见孙级有限公司", "91110000000000103X",
                    3, archivedMiddle, 0L, List.of("DIRECT", "AGENT")));
            String directChild = command.create(createCommand(
                    "可见直属下级", "可见直属下级有限公司", "91110000000000104X",
                    2, root, 0L, List.of("CHANNEL")));

            LocalDateTime olderCreatedAt = LocalDateTime.of(2026, 9, 1, 9, 0);
            LocalDateTime newerCreatedAt = LocalDateTime.of(2026, 9, 2, 9, 0);
            BuyerDO grandchildRow = buyerMapper.selectByBuyerCode(grandchild);
            grandchildRow.setCreatedAt(olderCreatedAt);
            buyerMapper.updateById(grandchildRow);
            BuyerDO directChildRow = buyerMapper.selectByBuyerCode(directChild);
            directChildRow.setCreatedAt(newerCreatedAt);
            buyerMapper.updateById(directChildRow);

            LocalDateTime projectTime = LocalDateTime.of(2026, 9, 3, 9, 0);
            insertProject(projectMapper, "PRJ_DESC_ACTIVE", grandchildRow.getId(),
                    BuyerProjectStatus.ACTIVE, projectTime);
            insertProject(projectMapper, "PRJ_DESC_ARCHIVED", grandchildRow.getId(),
                    BuyerProjectStatus.ARCHIVED, projectTime);
            command.changeStatus(archivedMiddle, BuyerStatus.ARCHIVED, 1L);

            List<BuyerChildItem> descendants = query.children(root);

            assertThat(descendants).extracting(BuyerChildItem::buyerCode)
                    .containsExactly(directChild, grandchild);
            BuyerChildItem direct = descendants.getFirst();
            assertThat(direct.distributionLevel()).isEqualTo(2);
            assertThat(direct.cooperationModes()).containsExactly("CHANNEL");
            assertThat(direct.projectCount()).isZero();
            assertThat(direct.createdAt()).isEqualTo(newerCreatedAt);
            BuyerChildItem grand = descendants.get(1);
            assertThat(grand.distributionLevel()).isEqualTo(3);
            assertThat(grand.cooperationModes()).containsExactly("DIRECT", "AGENT");
            assertThat(grand.projectCount()).isEqualTo(1L);
            assertThat(grand.status()).isEqualTo(BuyerStatus.ACTIVE.name());
            assertThat(grand.createdAt()).isEqualTo(olderCreatedAt);
            assertThat(query.children(grandchild)).isEmpty();
        }
    }

    private static void insertProject(BuyerProjectMapper projectMapper, String projectCode, Long buyerId,
                                      BuyerProjectStatus status, LocalDateTime now) {
        projectMapper.insert(BuyerProjectDO.builder()
                .projectCode(projectCode)
                .projectName(projectCode)
                .buyerId(buyerId)
                .catalogMode("MANAGED")
                .status(status.getCode())
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    /**
     * 插入 L1 和历史模板预留行后关联分销商品、写 CUSTOM 覆盖、再 FOLLOW_TEMPLATE 清空指针。
     * 查询与冻结均须忽略历史模板行并回退 L1。
     * 设计见 {@code docs/superpowers/specs/2026-09-12-supply-chain-prd-scope-alignment-design.md#52-l2-与-follow_template}。
     */
    private static void addsProductAndIgnoresLegacyTemplatePrice(
            ConfigurableApplicationContext context, String buyerCode) {
        seedCatalog(context.getBean(ProductCatalogCommandService.class));
        PlatformSkuMapper skuMapper = context.getBean(PlatformSkuMapper.class);
        PlatformSettlementPriceMapper l1Mapper = context.getBean(PlatformSettlementPriceMapper.class);
        PlatformSettlementPriceVersionMapper l1VersionMapper =
                context.getBean(PlatformSettlementPriceVersionMapper.class);
        BuyerProductMapper productMapper = context.getBean(BuyerProductMapper.class);
        BuyerProductL2OverrideMapper overrideMapper = context.getBean(BuyerProductL2OverrideMapper.class);
        BuyerProductL2OverrideVersionMapper overrideVersionMapper =
                context.getBean(BuyerProductL2OverrideVersionMapper.class);
        BuyerProductSettlementPriceMapper templateMapper =
                context.getBean(BuyerProductSettlementPriceMapper.class);
        BuyerProductSettlementPriceVersionMapper templateVersionMapper =
                context.getBean(BuyerProductSettlementPriceVersionMapper.class);
        BuyerProductCommandService products = context.getBean(BuyerProductCommandService.class);
        BuyerProductQueryService productQuery = context.getBean(BuyerProductQueryService.class);

        PlatformSkuDO sku = skuMapper.selectByCode("SKU_001_100");
        assertThat(sku).isNotNull();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        PlatformSettlementPriceVersionDO l1Version = PlatformSettlementPriceVersionDO.builder()
                .platformSkuId(sku.getId())
                .versionNo(1)
                .unitPriceCents(L1_CENTS)
                .currency(Money.CURRENCY_CNY)
                .publishedBy(1L)
                .publishedAt(now)
                .createdAt(now)
                .build();
        l1VersionMapper.insert(l1Version);
        l1Mapper.insert(PlatformSettlementPriceDO.builder()
                .platformSkuId(sku.getId())
                .currentVersionId(l1Version.getId())
                .version(0L)
                .updatedAt(now)
                .build());

        List<String> codes = products.addProducts(buyerCode, List.of(
                new ProductLine("SKU_001_100", L2AdjustMode.FOLLOW_TEMPLATE, null, null, 5, null)), 1L);
        assertThat(codes).hasSize(1);
        String buyerProductCode = codes.getFirst();
        assertThat(buyerProductCode).startsWith("BPD_");

        BuyerProductDO product = productMapper.selectByCode(buyerProductCode);
        var listing = context.getBean(com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper.class)
                .selectByBuyerAndSkuCode(product.getBuyerId(), buyerProductCode);
        assertThat(listing).as("页面选品同事务建立唯一自动受理身份").isNotNull();
        assertThat(listing.getManagementMode()).isEqualTo("AUTO_SELECTION");
        assertThat(listing.getBuyerProductId()).isEqualTo(product.getId());
        assertThat(listing.getStatus()).isEqualTo(com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingStatus.DRAFT.getCode());
        assertThat(listing.getCurrentVersionId()).isNull();
        var projection = context.getBean(com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingAvailabilityMapper.class)
                .selectByListingId(listing.getId());
        assertThat(projection.getReasonCode()).isEqualTo("NOT_EVALUATED");
        assertThat(projection.getAvailability()).isEqualTo(com.aifuli.module.supplychain.tradecore.catalog.domain.ListingAvailability.UNKNOWN.getCode());
        BuyerProductSettlementPriceVersionDO legacyTemplateVersion = BuyerProductSettlementPriceVersionDO.builder()
                .buyerProductId(product.getId())
                .versionNo(1)
                .unitPriceCents(LEGACY_TEMPLATE_CENTS)
                .currency(Money.CURRENCY_CNY)
                .publishedAt(now)
                .createdAt(now)
                .build();
        templateVersionMapper.insert(legacyTemplateVersion);
        templateMapper.insert(BuyerProductSettlementPriceDO.builder()
                .buyerProductId(product.getId())
                .currentVersionId(legacyTemplateVersion.getId())
                .version(0L)
                .updatedAt(now)
                .build());
        var listed = productQuery.page(buyerCode, null, 1, 20).getList();
        assertThat(productQuery.page(buyerCode, List.of("MOVIE"), 1, 20).getList())
                .extracting(BuyerProductListItem::buyerProductCode).containsExactly(buyerProductCode);
        assertThat(productQuery.page(buyerCode, List.of("MOVIE", "FOOD"), 1, 20).getList())
                .extracting(BuyerProductListItem::buyerProductCode).containsExactly(buyerProductCode);
        assertThat(productQuery.page(buyerCode, List.of("FOOD,OTHER"), 1, 20).getList()).isEmpty();
        assertThat(productQuery.page(buyerCode, List.of("OTHER_TYPE"), 1, 20).getTotal()).isEqualTo(0L);
        assertThat(listed).extracting(BuyerProductListItem::buyerProductCode).containsExactly(buyerProductCode);
        assertThat(listed.getFirst().sellableCategory()).isEqualTo("MOVIE");
        assertThat(listed.getFirst().productType()).isEqualTo("MOVIE");
        assertThat(listed.getFirst().productClassification()).isEqualTo("MOVIE_TICKET");
        assertThat(listed.getFirst().suggestPriceCents()).isEqualTo(L1_CENTS);
        assertThat(listed.getFirst().distPriceCents()).isEqualTo(L1_CENTS);
        assertThat(listed.getFirst().finalPriceCents()).isEqualTo(L1_CENTS);
        assertThat(listed.getFirst().pricingRule()).isEqualTo("继承L1");
        assertThat(listed.getFirst().displayThresholdPercent()).isEqualTo(5);

        products.patchL2(buyerCode, buyerProductCode, L2AdjustMode.CUSTOM, CUSTOM_PERCENT, 0L, 5, 1L);
        BuyerProductL2OverrideDO override = overrideMapper.selectByBuyerProductId(product.getId());
        assertThat(override).isNotNull();
        assertThat(override.getCurrentVersionId()).isNotNull();
        BuyerProductL2OverrideVersionDO version =
                overrideVersionMapper.selectById(override.getCurrentVersionId());
        assertThat(version.getAdjustMode()).isEqualTo(L2AdjustMode.CUSTOM.name());
        assertThat(version.getResolvedUnitPriceCents()).isEqualTo(CUSTOM_RESOLVED_CENTS);
        assertThat(templateMapper.selectByBuyerProductId(product.getId()).getCurrentVersionId())
                .isEqualTo(legacyTemplateVersion.getId());

        var afterCustom = productQuery.page(buyerCode, null, 1, 20).getList().getFirst();
        assertThat(afterCustom.distPriceCents()).isEqualTo(CUSTOM_RESOLVED_CENTS);
        assertThat(afterCustom.finalPriceCents()).isEqualTo(CUSTOM_RESOLVED_CENTS);
        assertThat(afterCustom.pricingRule()).isEqualTo("自定义公式");

        products.patchL2(buyerCode, buyerProductCode, L2AdjustMode.FOLLOW_TEMPLATE, null, null, 5, 1L);
        BuyerProductL2OverrideDO cleared = overrideMapper.selectByBuyerProductId(product.getId());
        assertThat(cleared.getCurrentVersionId()).isNull();
        var afterFollowTemplate = productQuery.page(buyerCode, null, 1, 20).getList().getFirst();
        assertThat(afterFollowTemplate.distPriceCents()).isEqualTo(L1_CENTS);
        assertThat(afterFollowTemplate.finalPriceCents()).isEqualTo(L1_CENTS);
        assertThat(afterFollowTemplate.pricingRule()).isEqualTo("继承L1");
        assertThat(templateMapper.selectByBuyerProductId(product.getId()).getCurrentVersionId())
                .isEqualTo(legacyTemplateVersion.getId());
    }

    private static void seedCatalog(ProductCatalogCommandService catalog) {
        catalog.createBrand(new ProductCatalogCommandService.CreateBrandCommand(
                "BRD_001", "影院品牌", null, null, 0, 1L));
        catalog.changeBrandStatus("BRD_001", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createCategory(new ProductCatalogCommandService.CreateCategoryCommand(
                "CAT_001", "权益商品", null, 0, 1L));
        catalog.changeCategoryStatus("CAT_001", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createCategory(new ProductCatalogCommandService.CreateCategoryCommand(
                "CAT_001_001", "卡券", "CAT_001", 0, 1L));
        catalog.changeCategoryStatus("CAT_001_001", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createCategory(new ProductCatalogCommandService.CreateCategoryCommand(
                "CAT_002_001", "电影快充", "CAT_001_001", 0, 1L));
        catalog.changeCategoryStatus("CAT_002_001", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSpu(new ProductCatalogCommandService.CreateSpuCommand(
                "SPU_001", "BRD_001", "CAT_002_001",
                "MOVIE", "MOVIE", "MOVIE_TICKET",
                "电影票", null, null, null, 1L));
        catalog.changeSpuStatus("SPU_001", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSku(new ProductCatalogCommandService.CreateSkuCommand(
                "SKU_001_100", "SPU_001", "电影票 100", FulfillmentType.DIRECT_RECHARGE.name(),
                10_000L, Money.CURRENCY_CNY, 1L));
        catalog.changeSkuStatus("SKU_001_100", CatalogEntityStatus.ACTIVE, 1L);
    }

    private static CreateBuyerCommand levelOne(String name, Long opening) {
        return createCommand(name, "一级科技有限公司", "91110000000000001X", 1, null, opening);
    }

    private static CreateBuyerCommand levelTwoWithoutParent() {
        return createCommand("无上级二级", "无上级科技有限公司", "91110000000000002X", 2, null, 0L);
    }

    private static CreateBuyerCommand levelTwo(String name, String parentBuyerCode) {
        return createCommand(name, "二级科技有限公司", "91110000000000003X", 2, parentBuyerCode, 0L);
    }

    private static CreateBuyerCommand createCommand(String name, String legalEntity, String creditCode,
                                                    Integer level, String parentBuyerCode, Long opening) {
        return createCommand(name, legalEntity, creditCode, level, parentBuyerCode, opening, List.of());
    }

    private static CreateBuyerCommand createCommand(String name, String legalEntity, String creditCode,
                                                    Integer level, String parentBuyerCode, Long opening,
                                                    List<String> cooperationModes) {
        return new CreateBuyerCommand(
                name,
                List.of(new ContactLine("张三", "13800138000", "a@b.c")),
                parentBuyerCode,
                level,
                "ENTERPRISE",
                null,
                "MONTHLY",
                7,
                cooperationModes,
                List.of(),
                null,
                null,
                legalEntity,
                creditCode,
                "李四",
                "13900139000",
                "上海市浦东新区",
                "测试银行",
                "6222021234567890",
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                opening,
                1L,
                "buyer-admin-it");
    }

    private static UpdateBuyerCommand updateWithOpening(String buyerCode, Long opening) {
        return updateCommand(buyerCode, "一级", "一级科技有限公司", "91110000000000001X", 1, null, opening);
    }

    private static UpdateBuyerCommand changeLevelToTwoButKeepChild(String buyerCode) {
        return updateCommand(buyerCode, "一级", "一级科技有限公司", "91110000000000001X", 2, null, null);
    }

    private static UpdateBuyerCommand updateCommand(String buyerCode, String name, String legalEntity,
                                                    String creditCode, Integer level, String parentBuyerCode,
                                                    Long opening) {
        return updateCommand(buyerCode, name, legalEntity, creditCode, level, parentBuyerCode, opening, "测试银行");
    }

    private static UpdateBuyerCommand updateCommand(String buyerCode, String name, String legalEntity,
                                                    String creditCode, Integer level, String parentBuyerCode,
                                                    Long opening, String bankName) {
        return new UpdateBuyerCommand(
                buyerCode,
                name,
                List.of(new ContactLine("张三", "13800138000", "a@b.c")),
                parentBuyerCode,
                level,
                "ENTERPRISE",
                null,
                "MONTHLY",
                7,
                List.of(),
                List.of(),
                null,
                null,
                legalEntity,
                creditCode,
                "李四",
                "13900139000",
                "上海市浦东新区",
                bankName,
                "6222021234567890",
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                opening,
                1L, "buyer-admin-update-test");
    }

    private ConfigurableApplicationContext applicationContext() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("aifuli.supply-chain.enabled", true);
        properties.put("aifuli.supply-chain.datasource.url", jdbcUrl());
        properties.put("aifuli.supply-chain.datasource.username", username());
        properties.put("aifuli.supply-chain.datasource.password", password());
        properties.put("aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        properties.put("aifuli.supply-chain.security.key-version", "test-v1");
        properties.put("aifuli.supply-chain.security.envelope-key-base64", KEY_BASE64);
        properties.put("aifuli.supply-chain.security.fingerprint-key-base64", KEY_BASE64);
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
            AesGcmEnvelopeCrypto.class, ProductCatalogCommandService.class, ProductCenterTestConfiguration.class,
            SettlementPriceResolver.class,
            BuyerAccountOpeningService.class,
            BuyerAccountAdjustmentService.class,
            FundReservationService.class,
            AccountLedgerWriter.class,
            BuyerAdminCommandService.class, BuyerAdminQueryService.class,
            BuyerInvoiceService.class,
            BuyerProductCommandService.class, BuyerProductQueryService.class,
            com.aifuli.module.supplychain.tradecore.catalog.application.BuyerAcceptanceCatalogReadService.class,
            com.aifuli.module.supplychain.tradecore.catalog.application.BuyerProductSelectionBatchTransactionalWorker.class,
            com.aifuli.module.supplychain.tradecore.catalog.application.BuyerAcceptanceCatalogIdentityWriter.class,
            com.aifuli.module.supplychain.tradecore.catalog.application.BuyerAcceptanceCatalogIdentityProbe.class,
            GenericBatchPricingService.class})
    static class TestConfiguration {
        @Bean
        static AdjustmentLedgerFailureInjector adjustmentLedgerFailureInjector() {
            return new AdjustmentLedgerFailureInjector();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        TaxRateDictionaryAdapter taxRateDictionaryAdapter() {
            TaxRateDictionaryAdapter adapter = mock(TaxRateDictionaryAdapter.class);
            when(adapter.requireEnabled(any(), anyString())).thenAnswer(invocation ->
                    new TaxMetadata(invocation.getArgument(0), invocation.getArgument(1), "不征税"));
            return adapter;
        }
    }
}
