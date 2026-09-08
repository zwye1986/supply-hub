package com.aifuli.module.supplychain.border.instore.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.application.facade.InstoreBuyerCashierCallbackFacade;
import com.aifuli.module.supplychain.application.facade.InstoreChannelCommandFacade;
import com.aifuli.module.supplychain.border.instore.domain.InstoreRefundStatus;
import com.aifuli.module.supplychain.border.order.domain.CardOrderSummaryPolicy;
import com.aifuli.module.supplychain.border.order.domain.InstoreOrderSummaryPolicy;
import com.aifuli.module.supplychain.border.order.domain.O2OOrderSummaryPolicy;
import com.aifuli.module.supplychain.border.order.domain.OrderSummaryPolicyRegistry;
import com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.integration.buyer.BuyerCashierPort;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRouteResolver;
import com.aifuli.module.supplychain.tradecore.account.application.BuyerAccountReturnService;
import com.aifuli.module.supplychain.tradecore.account.application.FundReservationService;
import com.aifuli.module.supplychain.tradecore.ledger.application.AccountLedgerWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 MySQL 纵向测试：INSTORE 退款串行闭环——受理排队、抢占派发外呼（原 refundCaseNo）、
 * 回调收敛、幂等账户返还（REFUND_RETURN:{refundCaseNo} 账本键）、订单累计、售后投影与派发下一个排队 Case。
 *
 * <p>测试导入真实 Mapper/Lifecycle/收敛服务/Facade/账户返还服务，仅以
 * {@link RecordingCashierPort} 替换 {@link BuyerCashierPort}（记录外呼命令、返回可配置结果）。
 * 验证点：同一支付流水至多一个活动退款；可退 = 已付 − 已累计 − 在途；金额不一致转 MANUAL
 * 且不返还；回调优先与权威查询兜底共用收敛路径且终态幂等。详见
 * {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#45-生产事务与幂等不变量}
 * 与计划 {@code docs/superpowers/plans/2026-08-23-instore-refund-serialization.md#Task-9-退款串行纵向集成测试真实-mysql}。</p>
 */
class InstoreRefundFlowIntegrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain")
            .withUsername("supply_chain")
            .withPassword("supply_chain");

    private static final String EXTERNAL_JDBC_URL =
            System.getProperty("supply-chain.test.jdbc-url");

    private static final String CHANNEL_RT_MART = "RT_MART";
    private static final String SOURCE_SYSTEM = "AIFULI_MALL";
    private static final long PAY_AMOUNT = 100_000L;

    /** 场景 A：串行受理 + 排队 + 回调收敛 + 幂等回放（同一 Spring 上下文内顺序推进）。 */
    private static final Scenario SERIAL =
            new Scenario(3001L, 3031L, 301L, 831L, 931L, 631L, 681L, 801L, 851L, 881L, "A");
    /** 场景 B：实际退款金额与申请不一致转人工。 */
    private static final Scenario MISMATCH =
            new Scenario(3101L, 3131L, 311L, 841L, 941L, 641L, 691L, 811L, 861L, 891L, "B");
    /** 场景 C：回调丢失时按原 refundCaseNo 权威查询兜底收敛。 */
    private static final Scenario QUERY_FALLBACK =
            new Scenario(3201L, 3231L, 321L, 851L, 951L, 651L, 701L, 821L, 871L, 901L, "C");
    /** 场景 D：退款金额领域不变量与幂等请求指纹冲突。 */
    private static final Scenario IDEMPOTENCY_GUARD =
            new Scenario(3301L, 3331L, 331L, 861L, 961L, 661L, 711L, 831L, 881L, 911L, "D");

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
    void serialAcceptanceQueuesConvergesReturnsFundsAndReplaysIdempotently() {
        try (ConfigurableApplicationContext context = applicationContext()) {
            InstoreChannelCommandFacade commandFacade =
                    context.getBean(InstoreChannelCommandFacade.class);
            InstoreBuyerCashierCallbackFacade callbackFacade =
                    context.getBean(InstoreBuyerCashierCallbackFacade.class);
            RecordingCashierPort cashierPort = context.getBean(RecordingCashierPort.class);
            JdbcTemplate jdbc = new JdbcTemplate(
                    context.getBean("supplyChainDataSource", DataSource.class));
            insertPaidScenario(jdbc, SERIAL);

            // 场景 1：首笔受理抢占派发，外呼使用原 refundCaseNo + 原 invocationId（operationNo）
            InstoreChannelCommandFacade.RefundAcceptance first = commandFacade.acceptRefund(
                    new InstoreChannelCommandFacade.AcceptRefundCommand(SERIAL.projectId(), CHANNEL_RT_MART,
                            SERIAL.channelRefundNo(1), SERIAL.tradeId(), "CHANNEL_REFUND",
                            30_000L, "trace-rfd-a1"));
            assertThat(first.claimedForDispatch()).isTrue();
            assertThat(first.refundStatus()).isEqualTo(InstoreRefundStatus.BUYER_REFUNDING.getCode());
            assertThat(cashierPort.refundCommands()).hasSize(1);
            BuyerCashierPort.RefundCommand firstCall = cashierPort.refundCommands().getFirst();
            assertThat(firstCall.refundCaseNo()).isEqualTo(first.refundCaseNo());
            assertThat(firstCall.outTradeNo()).isEqualTo(SERIAL.outTradeNo());
            assertThat(firstCall.amountCny()).isEqualTo(30_000L);
            assertThat(firstCall.invocationId()).isEqualTo(refundOperationNo(jdbc, first.refundCaseNo()));
            assertThat(firstCall.route().projectId()).isEqualTo(SERIAL.projectId());
            assertThat(firstCall.route().profileVersionId()).isEqualTo(SERIAL.versionId());
            assertThat(firstCall.route().sourceSystem()).isEqualTo(SOURCE_SYSTEM);
            assertThat(firstCall.route().externalTenantId()).isEqualTo(SERIAL.tenantId());
            assertThat(activeRefundCaseId(jdbc, SERIAL.instoreOrderId()))
                    .isEqualTo(refundCaseId(jdbc, first.refundCaseNo()));

            // 场景 1：第二笔排队不外呼（同一支付流水至多一个活动退款）
            InstoreChannelCommandFacade.RefundAcceptance second = commandFacade.acceptRefund(
                    new InstoreChannelCommandFacade.AcceptRefundCommand(SERIAL.projectId(), CHANNEL_RT_MART,
                            SERIAL.channelRefundNo(2), SERIAL.tradeId(), "CHANNEL_REFUND",
                            20_000L, "trace-rfd-a2"));
            assertThat(second.claimedForDispatch()).isFalse();
            assertThat(second.refundStatus()).isEqualTo(InstoreRefundStatus.QUEUED.getCode());
            assertThat(cashierPort.refundCommands()).hasSize(1);

            // 场景 1：可退 = 已付 − 已累计 − 在途（30000 + 20000 在途），50001 超限拒绝
            assertThatThrownBy(() -> commandFacade.acceptRefund(
                    new InstoreChannelCommandFacade.AcceptRefundCommand(SERIAL.projectId(), CHANNEL_RT_MART,
                            SERIAL.channelRefundNo(3), SERIAL.tradeId(), "CHANNEL_REFUND",
                            50_001L, "trace-rfd-a3")))
                    .isInstanceOf(ServiceException.class)
                    .extracting(error -> ((ServiceException) error).getCode())
                    .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_REFUND_AMOUNT_EXCEEDED.getCode());
            assertThat(cashierPort.refundCommands()).hasSize(1);

            // 场景 2：回调收敛成功——账户返还、账本 REFUND 条目、订单头三项累计、活动指针切换
            callbackFacade.confirmRefund(new InstoreBuyerCashierCallbackFacade.RefundCallbackCommand(
                    SERIAL.projectId(), first.refundCaseNo(), 30_000L, "CRF_RFD_A1", "trace-cb-a1"));
            assertThat(refundStatusOf(jdbc, first.refundCaseNo()))
                    .isEqualTo(InstoreRefundStatus.SUCCESS.getCode());
            assertThat(accountBalance(jdbc, SERIAL.buyerId())).isEqualTo(30_000L);
            Map<String, Object> firstEntry = refundLedgerEntry(jdbc, SERIAL.accountId(),
                    "REFUND_RETURN:" + first.refundCaseNo());
            assertThat(firstEntry.get("entry_type")).isEqualTo("REFUND");
            assertThat(firstEntry.get("balance_delta")).isEqualTo(30_000L);
            assertThat(firstEntry.get("frozen_delta")).isEqualTo(0L);
            assertThat(refundTotals(jdbc, SERIAL.instoreOrderId()))
                    .containsExactly(30_000L, 30_000L, 30_000L);
            assertThat(refundCaseField(jdbc, first.refundCaseNo(), "cashier_refund_reference"))
                    .isEqualTo("CRF_RFD_A1");
            assertThat(refundCaseField(jdbc, first.refundCaseNo(), "buyer_actual_refund_amount"))
                    .isEqualTo(30_000L);

            // 场景 2：终态即释放串行锁——排队 Case 自动抢占派发并按原 refundCaseNo 外呼
            assertThat(cashierPort.refundCommands()).hasSize(2);
            assertThat(cashierPort.refundCommands().get(1).refundCaseNo())
                    .isEqualTo(second.refundCaseNo());
            assertThat(refundStatusOf(jdbc, second.refundCaseNo()))
                    .isEqualTo(InstoreRefundStatus.BUYER_REFUNDING.getCode());
            assertThat(activeRefundCaseId(jdbc, SERIAL.instoreOrderId()))
                    .isEqualTo(refundCaseId(jdbc, second.refundCaseNo()));
            // 在途（第二个 Case 退款中）优先：售后投影为 10 处理中
            assertThat(aftersaleStatusOf(jdbc, SERIAL.orderId())).isEqualTo((short) 10);

            // 场景 2：第二笔回调收敛成功——累计 50000/100000，无未收敛 Case 后投影 20 部分退款
            callbackFacade.confirmRefund(new InstoreBuyerCashierCallbackFacade.RefundCallbackCommand(
                    SERIAL.projectId(), second.refundCaseNo(), 20_000L, "CRF_RFD_A2", "trace-cb-a2"));
            assertThat(refundStatusOf(jdbc, second.refundCaseNo()))
                    .isEqualTo(InstoreRefundStatus.SUCCESS.getCode());
            assertThat(accountBalance(jdbc, SERIAL.buyerId())).isEqualTo(50_000L);
            assertThat(refundLedgerCount(jdbc, SERIAL.accountId())).isEqualTo(2L);
            assertThat(refundTotals(jdbc, SERIAL.instoreOrderId()))
                    .containsExactly(50_000L, 50_000L, 50_000L);
            assertThat(activeRefundCaseId(jdbc, SERIAL.instoreOrderId())).isNull();
            assertThat(aftersaleStatusOf(jdbc, SERIAL.orderId())).isEqualTo((short) 20);
            assertThat(refundCaseEventCount(jdbc, first.refundCaseNo(), second.refundCaseNo()))
                    .isEqualTo(2L);

            // 场景 5：终态回调幂等回放——无二次返还、无二次账本、无重复派发、无状态冲突异常
            callbackFacade.confirmRefund(new InstoreBuyerCashierCallbackFacade.RefundCallbackCommand(
                    SERIAL.projectId(), first.refundCaseNo(), 30_000L, "CRF_RFD_A1", "trace-cb-a1b"));
            assertThat(accountBalance(jdbc, SERIAL.buyerId())).isEqualTo(50_000L);
            assertThat(refundLedgerCount(jdbc, SERIAL.accountId())).isEqualTo(2L);
            assertThat(cashierPort.refundCommands()).hasSize(2);
            assertThat(refundStatusOf(jdbc, first.refundCaseNo()))
                    .isEqualTo(InstoreRefundStatus.SUCCESS.getCode());
        }
    }

    @Test
    void actualAmountMismatchTransitionsRefundCaseToManualWithoutReturn() {
        try (ConfigurableApplicationContext context = applicationContext()) {
            InstoreChannelCommandFacade commandFacade =
                    context.getBean(InstoreChannelCommandFacade.class);
            InstoreBuyerCashierCallbackFacade callbackFacade =
                    context.getBean(InstoreBuyerCashierCallbackFacade.class);
            JdbcTemplate jdbc = new JdbcTemplate(
                    context.getBean("supplyChainDataSource", DataSource.class));
            insertPaidScenario(jdbc, MISMATCH);

            InstoreChannelCommandFacade.RefundAcceptance accepted = commandFacade.acceptRefund(
                    new InstoreChannelCommandFacade.AcceptRefundCommand(MISMATCH.projectId(), CHANNEL_RT_MART,
                            MISMATCH.channelRefundNo(1), MISMATCH.tradeId(), "CHANNEL_REFUND",
                            30_000L, "trace-rfd-b1"));
            assertThat(accepted.claimedForDispatch()).isTrue();

            // 实际退款 29999 与申请 30000 不一致：Case 已在事务内转 MANUAL，异常在事务提交后抛出
            assertThatThrownBy(() -> callbackFacade.confirmRefund(
                    new InstoreBuyerCashierCallbackFacade.RefundCallbackCommand(
                            MISMATCH.projectId(), accepted.refundCaseNo(), 29_999L,
                            "CRF_RFD_B1", "trace-cb-b1")))
                    .isInstanceOf(ServiceException.class)
                    .extracting(error -> ((ServiceException) error).getCode())
                    .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_REFUND_AMOUNT_MISMATCH.getCode());
            assertThat(refundStatusOf(jdbc, accepted.refundCaseNo()))
                    .isEqualTo(InstoreRefundStatus.MANUAL.getCode());
            assertThat(refundCaseField(jdbc, accepted.refundCaseNo(), "result_reason_code"))
                    .isEqualTo("REFUND_AMOUNT_MISMATCH");

            // 不返还、不累计：账户余额与账本保持不变，活动指针释放
            assertThat(accountBalance(jdbc, MISMATCH.buyerId())).isEqualTo(0L);
            assertThat(refundLedgerCount(jdbc, MISMATCH.accountId())).isEqualTo(0L);
            assertThat(refundTotals(jdbc, MISMATCH.instoreOrderId()))
                    .containsExactly(0L, 0L, 0L);
            assertThat(activeRefundCaseId(jdbc, MISMATCH.instoreOrderId())).isNull();

            // 人工 Case 属于未收敛协同：售后投影保持 10 处理中
            assertThat(aftersaleStatusOf(jdbc, MISMATCH.orderId())).isEqualTo((short) 10);
        }
    }

    @Test
    void authoritativeQueryConvergesRefundWhenCallbackLost() {
        try (ConfigurableApplicationContext context = applicationContext()) {
            InstoreChannelCommandFacade commandFacade =
                    context.getBean(InstoreChannelCommandFacade.class);
            RecordingCashierPort cashierPort = context.getBean(RecordingCashierPort.class);
            JdbcTemplate jdbc = new JdbcTemplate(
                    context.getBean("supplyChainDataSource", DataSource.class));
            insertPaidScenario(jdbc, QUERY_FALLBACK);

            InstoreChannelCommandFacade.RefundAcceptance accepted = commandFacade.acceptRefund(
                    new InstoreChannelCommandFacade.AcceptRefundCommand(QUERY_FALLBACK.projectId(), CHANNEL_RT_MART,
                            QUERY_FALLBACK.channelRefundNo(1), QUERY_FALLBACK.tradeId(),
                            "CHANNEL_REFUND", 40_000L, "trace-rfd-c1"));
            assertThat(accepted.claimedForDispatch()).isTrue();
            assertThat(cashierPort.refundCommands()).hasSize(1);

            // 回调丢失：渠道按 (channelCode, channelRefundNo) 发起权威查询，本地非终态时按原 refundCaseNo 外呼
            cashierPort.stubQueryRefund(BuyerCashierPort.RefundQueryStatus.SUCCEEDED,
                    40_000L, "CRF_RFD_C1");
            InstoreChannelCommandFacade.RefundStatusView view = commandFacade.queryAndConvergeRefund(
                    new InstoreChannelCommandFacade.QueryRefundCommand(QUERY_FALLBACK.projectId(),
                            CHANNEL_RT_MART, QUERY_FALLBACK.channelRefundNo(1), "trace-q-c1"));
            assertThat(view.status()).isEqualTo(InstoreRefundStatus.SUCCESS.getCode());
            assertThat(view.buyerActualRefundAmount()).isEqualTo(40_000L);
            assertThat(cashierPort.queryRefundCommands()).hasSize(1);
            assertThat(cashierPort.queryRefundCommands().getFirst().refundCaseNo())
                    .isEqualTo(accepted.refundCaseNo());

            // 与回调路径共用收敛：账户返还、三项累计、售后投影与活动指针释放
            assertThat(refundStatusOf(jdbc, accepted.refundCaseNo()))
                    .isEqualTo(InstoreRefundStatus.SUCCESS.getCode());
            assertThat(accountBalance(jdbc, QUERY_FALLBACK.buyerId())).isEqualTo(40_000L);
            assertThat(refundLedgerCount(jdbc, QUERY_FALLBACK.accountId())).isEqualTo(1L);
            assertThat(refundTotals(jdbc, QUERY_FALLBACK.instoreOrderId()))
                    .containsExactly(40_000L, 40_000L, 40_000L);
            assertThat(activeRefundCaseId(jdbc, QUERY_FALLBACK.instoreOrderId())).isNull();
            assertThat(aftersaleStatusOf(jdbc, QUERY_FALLBACK.orderId())).isEqualTo((short) 20);
        }
    }

    @Test
    void nonPositiveAmountAndChangedReplayAreRejectedWithoutCreatingOrDispatchingCase() {
        try (ConfigurableApplicationContext context = applicationContext()) {
            InstoreChannelCommandFacade commandFacade =
                    context.getBean(InstoreChannelCommandFacade.class);
            RecordingCashierPort cashierPort = context.getBean(RecordingCashierPort.class);
            JdbcTemplate jdbc = new JdbcTemplate(
                    context.getBean("supplyChainDataSource", DataSource.class));
            insertPaidScenario(jdbc, IDEMPOTENCY_GUARD);

            String invalidRefundNo = IDEMPOTENCY_GUARD.channelRefundNo(1);
            assertThatThrownBy(() -> commandFacade.acceptRefund(
                    new InstoreChannelCommandFacade.AcceptRefundCommand(IDEMPOTENCY_GUARD.projectId(),
                            CHANNEL_RT_MART, invalidRefundNo, IDEMPOTENCY_GUARD.tradeId(),
                            "CHANNEL_REFUND", 0L, "trace-rfd-d-invalid")))
                    .isInstanceOf(ServiceException.class)
                    .extracting(error -> ((ServiceException) error).getCode())
                    .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_REFUND_AMOUNT_INVALID.getCode());
            assertThat(refundCaseCount(jdbc, invalidRefundNo)).isZero();
            assertThat(cashierPort.refundCommands()).isEmpty();

            String replayRefundNo = IDEMPOTENCY_GUARD.channelRefundNo(2);
            InstoreChannelCommandFacade.RefundAcceptance accepted = commandFacade.acceptRefund(
                    new InstoreChannelCommandFacade.AcceptRefundCommand(IDEMPOTENCY_GUARD.projectId(),
                            CHANNEL_RT_MART, replayRefundNo, IDEMPOTENCY_GUARD.tradeId(),
                            "CHANNEL_REFUND", 30_000L, "trace-rfd-d-accept"));
            assertThat(accepted.claimedForDispatch()).isTrue();
            assertThat(cashierPort.refundCommands()).hasSize(1);

            assertThatThrownBy(() -> commandFacade.acceptRefund(
                    new InstoreChannelCommandFacade.AcceptRefundCommand(IDEMPOTENCY_GUARD.projectId(),
                            CHANNEL_RT_MART, replayRefundNo, IDEMPOTENCY_GUARD.tradeId(),
                            "CHANNEL_REFUND", 29_999L, "trace-rfd-d-conflict")))
                    .isInstanceOf(ServiceException.class)
                    .extracting(error -> ((ServiceException) error).getCode())
                    .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_IDENTITY_CONFLICT.getCode());
            assertThat(refundCaseCount(jdbc, replayRefundNo)).isEqualTo(1L);
            assertThat(cashierPort.refundCommands()).hasSize(1);
        }
    }

    /** 场景夹具主键与稳定业务号集合；三个场景互不重叠，支持共享数据库下并行演进。 */
    private record Scenario(long buyerId, long accountId, long projectId, long profileId,
                            long versionId, long bindingId, long supplierBindingId,
                            long orderId, long instoreOrderId, long paymentFlowId,
                            String suffix) {

        String buyerCode() {
            return "BUYER_RFD_" + suffix;
        }

        String accountNo() {
            return "BAC_RFD_" + suffix;
        }

        String projectCode() {
            return "INSTORE_RFD_" + suffix;
        }

        String tenantId() {
            return "tenant-rfd-" + suffix.toLowerCase();
        }

        String buyerUser() {
            return "user-rfd-" + suffix.toLowerCase();
        }

        String companyCode() {
            return "COMPANY_RFD_" + suffix;
        }

        String orderNo() {
            return "SCO_RFD_" + suffix;
        }

        String buyerOrderNo() {
            return "RT_RFD_ORDER_" + suffix;
        }

        String instoreOrderNo() {
            return "ICO_RFD_" + suffix;
        }

        String paymentFlowNo() {
            return "IPF_RFD_" + suffix;
        }

        String tradeId() {
            return "TRADE_RFD_" + suffix;
        }

        String outTradeNo() {
            return "OT_RFD_" + suffix;
        }

        String channelRefundNo(int sequence) {
            return "RT_REFUND_" + suffix + sequence;
        }
    }

    /** 插入已支付场景夹具：采购商（余额 0 账户）/项目/CASHIER 路由/稳定绑定/总订单/渠道订单头/PAID 支付流水。 */
    private static void insertPaidScenario(JdbcTemplate jdbc, Scenario scenario) {
        jdbc.update("""
                INSERT INTO sc_buyer(
                    id, buyer_code, buyer_name, short_name, buyer_type,
                    status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'SAAS_MALL', 10, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, scenario.buyerId(), scenario.buyerCode(), "退款流程采购商" + scenario.suffix(),
                scenario.buyerCode());
        jdbc.update("""
                INSERT INTO sc_buyer_account(
                    id, account_no, buyer_id, currency, balance, frozen, status, version,
                    created_at, updated_at)
                VALUES (?, ?, ?, 'CNY', 0, 0, 10, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, scenario.accountId(), scenario.accountNo(), scenario.buyerId());
        jdbc.update("""
                INSERT INTO sc_buyer_project(
                    id, project_code, project_name, buyer_id, catalog_mode,
                    status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'INDEPENDENT', 10, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, scenario.projectId(), scenario.projectCode(),
                "退款流程项目" + scenario.suffix(), scenario.buyerId());
        insertCashierRoute(jdbc, scenario);
        jdbc.update("""
                INSERT INTO sc_instore_channel_user_binding(
                    id, binding_no, buyer_id, project_id, source_system, external_tenant_id,
                    buyer_user_id, channel_code, status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'RT_MART', 10, 0,
                        UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, scenario.bindingId(), "IUB_RFD_" + scenario.suffix(), scenario.buyerId(),
                scenario.projectId(), SOURCE_SYSTEM, scenario.tenantId(), scenario.buyerUser());
        jdbc.update("""
                INSERT INTO sc_supplier_channel_binding(
                    id, channel_code, external_merchant_id, supplier_id, mapping_version,
                    valid_from, current_flag, status, version, created_at, updated_at)
                VALUES (?, 'RT_MART', ?, 301, 1, UTC_TIMESTAMP(3), 1, 10, 0,
                        UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, scenario.supplierBindingId(), scenario.companyCode());
        jdbc.update("""
                INSERT INTO sc_supply_chain_order(
                    id, order_no, order_type, buyer_id, buyer_code_snapshot, buyer_name_snapshot,
                    buyer_user_id, buyer_order_no, acceptance_receipt_id, display_supplier_id,
                    buyer_settlement_amount, currency, order_status, aftersale_status,
                    business_stage, detail_type, detail_id, detail_version,
                    request_fingerprint, version, created_at, updated_at)
                VALUES (?, ?, 'INSTORE', ?, ?, ?, ?, ?, NULL, 301, ?, 'CNY', 30, 0,
                        'INSTORE_PAID', 'INSTORE_CHANNEL', ?, 0,
                        UNHEX(SHA2(?, 256)), 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, scenario.orderId(), scenario.orderNo(), scenario.buyerId(),
                scenario.buyerCode(), "退款流程采购商" + scenario.suffix(), scenario.buyerUser(),
                scenario.buyerOrderNo(), PAY_AMOUNT, scenario.instoreOrderId(),
                "rfd-order-" + scenario.suffix());
        jdbc.update("""
                INSERT INTO sc_instore_channel_order(
                    id, instore_order_no, order_id, buyer_id, source_system, external_tenant_id,
                    project_id, cashier_profile_version_id, buyer_user_id, channel_code,
                    channel_user_binding_id, channel_order_id, company_code,
                    supplier_channel_binding_id, supplier_id, supplier_code_snapshot,
                    supplier_name_snapshot, total_amount, total_paid_amount, currency,
                    order_status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'RT_MART', ?, ?, ?, ?, 301, 'SUP_RTMART',
                        '大润发', ?, ?, 'CNY', 20, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, scenario.instoreOrderId(), scenario.instoreOrderNo(), scenario.orderId(),
                scenario.buyerId(), SOURCE_SYSTEM, scenario.tenantId(), scenario.projectId(),
                scenario.versionId(), scenario.buyerUser(), scenario.bindingId(),
                scenario.buyerOrderNo(), scenario.companyCode(), scenario.supplierBindingId(),
                PAY_AMOUNT, PAY_AMOUNT);
        jdbc.update("""
                INSERT INTO sc_instore_payment_flow(
                    id, payment_flow_no, instore_order_id, order_id, flow_sequence,
                    channel_code, trade_id, user_code, pay_amount, order_type, pay_status,
                    out_trade_no, buyer_paid_amount, external_transaction_id,
                    request_fingerprint, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, 'RT_MART', ?, ?, ?, 1, 20, ?, ?, ?,
                        UNHEX(SHA2(?, 256)), 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, scenario.paymentFlowId(), scenario.paymentFlowNo(),
                scenario.instoreOrderId(), scenario.orderId(), scenario.tradeId(),
                scenario.buyerUser(), PAY_AMOUNT, scenario.outTradeNo(), PAY_AMOUNT,
                "TXN_RFD_" + scenario.suffix(), "rfd-payment-" + scenario.suffix());
    }

    /** 插入 ACTIVE 档案、PUBLISHED 当前版本和启用 CASHIER 能力，形成可解析的收银台路由。 */
    private static void insertCashierRoute(JdbcTemplate jdbc, Scenario scenario) {
        jdbc.update("""
                INSERT INTO sc_buyer_integration_profile(
                    id, profile_no, buyer_id, project_id, profile_name, current_version_id,
                    status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 10, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, scenario.profileId(), "PROFILE_RFD_" + scenario.suffix(),
                scenario.buyerId(), scenario.projectId(),
                "退款收银台档案" + scenario.suffix(), scenario.versionId());
        jdbc.update("""
                INSERT INTO sc_buyer_integration_profile_version(
                    id, profile_id, version_no, status, effective_at, published_at,
                    change_reason, content_digest, current_flag, version, created_at, updated_at)
                VALUES (?, ?, 1, 10, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3),
                        '退款收银台路由首发', ?, 1, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, scenario.versionId(), scenario.profileId(), new byte[32]);
        jdbc.update("""
                INSERT INTO sc_buyer_integration_capability(
                    profile_version_id, capability_code, transport_mode, adapter_code,
                    contract_version, source_system, external_tenant_id, http_base_url,
                    credential_set_id, connect_timeout_ms, read_timeout_ms, total_timeout_ms,
                    max_response_bytes, bulkhead_concurrency, rate_limit_per_second,
                    circuit_breaker_policy, retry_policy_code, status, created_at)
                VALUES (?, 'CASHIER', 'RPC', 'SAAS_CASHIER_RPC', 'v1', ?, ?, NULL, NULL,
                        500, 1500, 2000, 65536, 20, 100, 'DEFAULT', 'QUERY_SAME_KEY', 10,
                        UTC_TIMESTAMP(3))
                """, scenario.versionId(), SOURCE_SYSTEM, scenario.tenantId());
    }

    private static Short refundStatusOf(JdbcTemplate jdbc, String refundCaseNo) {
        return jdbc.queryForObject(
                "SELECT status FROM sc_instore_refund_case WHERE refund_case_no = ?",
                Short.class, refundCaseNo);
    }

    private static Object refundCaseField(JdbcTemplate jdbc, String refundCaseNo, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM sc_instore_refund_case WHERE refund_case_no = ?",
                Object.class, refundCaseNo);
    }

    private static Long refundCaseId(JdbcTemplate jdbc, String refundCaseNo) {
        return jdbc.queryForObject(
                "SELECT id FROM sc_instore_refund_case WHERE refund_case_no = ?",
                Long.class, refundCaseNo);
    }

    private static String refundOperationNo(JdbcTemplate jdbc, String refundCaseNo) {
        return jdbc.queryForObject("""
                SELECT o.operation_no FROM sc_instore_operation o
                JOIN sc_instore_refund_case r ON r.id = o.refund_case_id
                WHERE r.refund_case_no = ?
                """, String.class, refundCaseNo);
    }

    private static Long activeRefundCaseId(JdbcTemplate jdbc, long instoreOrderId) {
        return jdbc.queryForObject(
                "SELECT active_refund_case_id FROM sc_instore_channel_order WHERE id = ?",
                Long.class, instoreOrderId);
    }

    private static long accountBalance(JdbcTemplate jdbc, long buyerId) {
        return jdbc.queryForObject(
                "SELECT balance FROM sc_buyer_account WHERE buyer_id = ?",
                Long.class, buyerId);
    }

    private static long refundLedgerCount(JdbcTemplate jdbc, long accountId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_account_ledger WHERE account_id = ? AND entry_type = 'REFUND'",
                Long.class, accountId);
    }

    private static Map<String, Object> refundLedgerEntry(JdbcTemplate jdbc, long accountId,
                                                          String entryKey) {
        return jdbc.queryForMap(
                "SELECT entry_type, balance_delta, frozen_delta FROM sc_account_ledger "
                        + "WHERE account_id = ? AND entry_key = ?",
                accountId, entryKey);
    }

    /** 订单头三项累计退款：渠道发起 / 采购商实际 / 账户返还。 */
    private static List<Long> refundTotals(JdbcTemplate jdbc, long instoreOrderId) {
        return jdbc.queryForObject(
                "SELECT total_channel_refund_amount, total_buyer_refund_amount, "
                        + "total_account_return_amount FROM sc_instore_channel_order WHERE id = ?",
                (rs, rowNum) -> List.of(rs.getLong(1), rs.getLong(2), rs.getLong(3)),
                instoreOrderId);
    }

    private static short aftersaleStatusOf(JdbcTemplate jdbc, long orderId) {
        return jdbc.queryForObject(
                "SELECT aftersale_status FROM sc_supply_chain_order WHERE id = ?",
                Short.class, orderId);
    }

    /** 退款 Case 终态通知 Outbox 条数（aggregate_id 为 refundCaseNo）。 */
    private static long refundCaseEventCount(JdbcTemplate jdbc, String firstCaseNo,
                                             String secondCaseNo) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_outbox_event WHERE aggregate_type = 'INSTORE_REFUND_CASE' "
                        + "AND aggregate_id IN (?, ?)",
                Long.class, firstCaseNo, secondCaseNo);
    }

    private static long refundCaseCount(JdbcTemplate jdbc, String channelRefundNo) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_instore_refund_case WHERE channel_refund_no = ?",
                Long.class, channelRefundNo);
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
                ? System.getProperty("supply-chain.test.jdbc-username", "root")
                : MYSQL.getUsername();
    }

    private static String password() {
        return EXTERNAL_JDBC_URL != null
                ? System.getProperty("supply-chain.test.jdbc-password", "")
                : MYSQL.getPassword();
    }

    /**
     * 确定性收银台 Port：记录 refund/queryRefund 外呼命令（验证原 refundCaseNo 与 invocationId），
     * 返回可配置结果；默认退款受理返回 PROCESSING，由回调或权威查询驱动收敛。
     */
    static class RecordingCashierPort implements BuyerCashierPort {

        private final List<RefundCommand> refundCommands = new ArrayList<>();
        private final List<QueryRefundCommand> queryRefundCommands = new ArrayList<>();
        private RefundQueryStatus queryRefundStatus = RefundQueryStatus.PROCESSING;
        private Long queryActualRefundAmountCny;
        private String queryCashierRefundReference;

        List<RefundCommand> refundCommands() {
            return refundCommands;
        }

        List<QueryRefundCommand> queryRefundCommands() {
            return queryRefundCommands;
        }

        void stubQueryRefund(RefundQueryStatus status, Long actualRefundAmountCny,
                             String cashierRefundReference) {
            this.queryRefundStatus = status;
            this.queryActualRefundAmountCny = actualRefundAmountCny;
            this.queryCashierRefundReference = cashierRefundReference;
        }

        @Override
        public RefundResult refund(RefundCommand command) {
            refundCommands.add(command);
            return new RefundResult(RefundStatus.PROCESSING, null, null, null,
                    OffsetDateTime.now(ZoneOffset.UTC));
        }

        @Override
        public QueryRefundResult queryRefund(QueryRefundCommand command) {
            queryRefundCommands.add(command);
            return new QueryRefundResult(queryRefundStatus, queryActualRefundAmountCny,
                    queryCashierRefundReference, null, OffsetDateTime.now(ZoneOffset.UTC));
        }

        @Override
        public CreateCashierOrderResult createCashierOrder(CreateCashierOrderCommand command) {
            throw new UnsupportedOperationException("refund flow test does not create cashier orders");
        }

        @Override
        public QueryPaymentResult queryPayment(QueryPaymentCommand command) {
            throw new UnsupportedOperationException("refund flow test does not query payments");
        }

        @Deprecated
        @Override
        public CreateCashierOrderResult createLegacyCashierOrder(LegacyCreateCashierOrderCommand command) {
            throw new UnsupportedOperationException("legacy cashier entry is forbidden for INSTORE");
        }

        @Deprecated
        @Override
        public QueryPaymentResult queryLegacyPayment(String outTradeNo) {
            throw new UnsupportedOperationException("legacy cashier entry is forbidden for INSTORE");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({SupplyChainDataSourceConfiguration.class, UlidGenerator.class, BusinessNoGenerator.class,
            InstoreRefundSequencer.class, AccountLedgerWriter.class, BuyerAccountReturnService.class,
            FundReservationService.class, OutboxEventRepository.class,
            OrderSummaryPolicyRegistry.class, CardOrderSummaryPolicy.class,
            O2OOrderSummaryPolicy.class, InstoreOrderSummaryPolicy.class,
            BuyerIntegrationRouteResolver.class, InstoreChannelLifecycle.class,
            InstoreChannelUserBindingResolver.class,
            InstorePaymentConvergenceService.class, InstoreRefundConvergenceService.class,
            InstoreChannelCommandFacade.class, InstoreBuyerCashierCallbackFacade.class})
    static class TestConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        BuyerCashierPort buyerCashierPort() {
            return new RecordingCashierPort();
        }
    }
}
