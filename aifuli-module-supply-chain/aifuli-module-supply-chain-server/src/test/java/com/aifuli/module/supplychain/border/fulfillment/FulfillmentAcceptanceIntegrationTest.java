package com.aifuli.module.supplychain.border.fulfillment;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.api.fulfillment.dto.AcceptFulfillmentReqDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentAcceptanceReceiptRespDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentReceiptStatus;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentTargetDTO;
import com.aifuli.module.supplychain.application.facade.BuyerFulfillmentCommandFacade;
import com.aifuli.module.supplychain.border.fulfillment.application.AcceptanceFailureInjector;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentAcceptanceLifecycle;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentTargetSchemaRegistry;
import com.aifuli.module.supplychain.border.fulfillment.application.SkuPurchaseLimitService;
import com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceRejectionCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOutboxCodes;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTargetFields;
import com.aifuli.module.supplychain.border.order.domain.CardOrderSummaryPolicy;
import com.aifuli.module.supplychain.border.order.domain.OrderSummaryPolicyRegistry;
import com.aifuli.module.supplychain.api.order.dto.SupplyChainAftersaleStatus;
import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.infrastructure.security.AesGcmEnvelopeCrypto;
import com.aifuli.module.supplychain.routing.application.RoutingPolicyPublicationService;
import com.aifuli.module.supplychain.routing.application.SupplierRoutingApplicationService;
import com.aifuli.module.supplychain.supplieradapter.metadata.ClosedJsonSchemaValidator;
import com.aifuli.module.supplychain.tradecore.account.application.BuyerAccountOpeningService;
import com.aifuli.module.supplychain.tradecore.account.application.FundReservationService;
import com.aifuli.module.supplychain.tradecore.account.dal.dataobject.BuyerAccountDO;
import com.aifuli.module.supplychain.tradecore.account.dal.mysql.BuyerAccountMapper;
import com.aifuli.module.supplychain.tradecore.account.domain.Money;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerCommandService;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerProjectAccessResolver;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerResolver;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProjectStatus;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerListingPublicationService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingAvailabilityDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingAvailabilityMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.FulfillmentType;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.ProjectQuoteKeyContext;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.StandardPricingQuoteService;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.StandardPricingQuoteService.FrozenQuote;
import com.aifuli.module.supplychain.tradecore.catalog.domain.ListingAvailability;
import com.aifuli.module.supplychain.tradecore.ledger.application.AccountLedgerWriter;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialSlotDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.InventorySourceDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSkuDailyQuotaDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialSlotMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.InventorySourceMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSkuDailyQuotaMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.ConnectorVersionStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.InventorySourceStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import com.aifuli.module.supplychain.testsupport.ProductCenterTestConfiguration;
import com.aifuli.module.supplychain.testsupport.RoutingPolicyPresetFixture;
import com.aifuli.module.supplychain.supplieradapter.metadata.SupplierConnectorCapabilityDO;
import com.aifuli.module.supplychain.supplieradapter.metadata.SupplierConnectorCapabilityMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_NOT_FOUND;

class FulfillmentAcceptanceIntegrationTest {

    private static final String PROJECT_A = "PRJ_LIMIT_A";
    private static final String PROJECT_B = "PRJ_LIMIT_B";

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain").withUsername("supply_chain").withPassword("supply_chain");
    private static final String EXTERNAL_JDBC_URL = System.getProperty("supply-chain.test.jdbc-url");
    private static final String KEY_BASE64 = Base64.getEncoder().encodeToString(new byte[32]);

    @BeforeEach
    void startContainer() {
        if (EXTERNAL_JDBC_URL == null) {
            MYSQL.start();
        }
    }

    @AfterEach
    void stopContainer() {
        if (EXTERNAL_JDBC_URL == null && MYSQL.isRunning()) {
            MYSQL.stop();
        }
    }

    @Test
    void acceptsAtomicallyWithPermanentIdempotencyAndRollsBackEveryInjectedStage() throws Exception {
        try (ConfigurableApplicationContext context = applicationContext()) {
            Fixture fixture = seed(context);
            BuyerFulfillmentCommandFacade facade = context.getBean(BuyerFulfillmentCommandFacade.class);
            MutableFailureInjector failures = context.getBean(MutableFailureInjector.class);
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));

            AcceptFulfillmentReqDTO first = request("C-CONCURRENT");
            List<FulfillmentAcceptanceReceiptRespDTO> concurrent = concurrently(20,
                    () -> facade.acceptFulfillment(first));
            assertThat(concurrent).hasSize(20);
            assertThat(concurrent).allSatisfy(response ->
                    assertThat(response.getReceiptStatus()).isIn(
                            FulfillmentReceiptStatus.PROCESSING, FulfillmentReceiptStatus.ACCEPTED));

            FulfillmentAcceptanceReceiptRespDTO accepted = facade.acceptFulfillment(first);
            assertThat(accepted.getReceiptStatus()).isEqualTo(FulfillmentReceiptStatus.ACCEPTED);
            assertThat(accepted.getIdempotentReplay()).isTrue();
            assertThat(accepted.getSupplierCode()).isEqualTo("QINAMENG");
            assertThat(accepted.getSupplierName()).isEqualTo("奇那梦");
            assertCounts(jdbc, 1, 1, 1, 1, 1, 1);
            assertThat(count(jdbc, "sc_supplier_routing_record")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_outbox_event WHERE event_type IN "
                    + "('" + FulfillmentOutboxCodes.EVENT_FULFILLMENT_OPERATION_READY + "','"
                    + FulfillmentOutboxCodes.EVENT_SUPPLY_CHAIN_ORDER_CHANGED + "','"
                    + FulfillmentOutboxCodes.EVENT_BUYER_ORDER_SUMMARY_CHANGED + "')", Long.class)).isEqualTo(3L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_account_ledger WHERE entry_type='FREEZE'",
                    Long.class)).isEqualTo(1L);
            byte[] encryptedTarget = jdbc.queryForObject("SELECT target_ciphertext FROM sc_fulfillment_request",
                    byte[].class);
            assertThat(new String(encryptedTarget, StandardCharsets.UTF_8)).doesNotContain("13800138000");
            assertThat(jdbc.queryForObject("SELECT target_schema_code FROM sc_fulfillment_request", String.class))
                    .isEqualTo("DIRECT_RECHARGE_TARGET");
            assertThat(jdbc.queryForObject("SELECT target_schema_version FROM sc_fulfillment_request", Integer.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT detail_version FROM sc_supply_chain_order", Long.class))
                    .isEqualTo(1L);
            Long projectAId = jdbc.queryForObject(
                    "SELECT id FROM sc_buyer_project WHERE project_code=?", Long.class, PROJECT_A);
            assertThat(jdbc.queryForObject(
                    "SELECT project_id FROM sc_fulfillment_acceptance_receipt WHERE buyer_order_no=?",
                    Long.class, first.getBuyerOrderNo())).isEqualTo(projectAId);
            assertThat(jdbc.queryForObject(
                    "SELECT project_id FROM sc_supply_chain_order WHERE buyer_order_no=?",
                    Long.class, first.getBuyerOrderNo())).isEqualTo(projectAId);
            assertThat(jdbc.queryForObject(
                    "SELECT fulfillment_account_value FROM sc_supply_chain_order WHERE buyer_order_no=?",
                    String.class, first.getBuyerOrderNo())).isEqualTo("13800138000");

            AcceptFulfillmentReqDTO prefixedReplay = copy(first);
            prefixedReplay.getTarget().setValues(Map.of("accountType", "MOBILE",
                    "accountValue", "008613800138000"));
            assertThat(facade.acceptFulfillment(prefixedReplay).getIdempotentReplay()).isTrue();

            // 购买人身份字段已删除：同键异参以实际目标手机号变更表达，仍必须幂等冲突。
            AcceptFulfillmentReqDTO conflicting = copy(first);
            conflicting.getTarget().setValues(Map.of("accountType", "MOBILE",
                    "accountValue", "13700137000"));
            assertThatThrownBy(() -> facade.acceptFulfillment(conflicting))
                    .isInstanceOf(ServiceException.class)
                    .extracting("code").isEqualTo(1_050_001_012);
            assertCounts(jdbc, 1, 1, 1, 1, 1, 1);

            // 购买人身份字段已删除：非法手机号身份的等价语义改为 MOBILE 目标不可规范化 → 最终拒绝，
            // 由回执收敛而非入口校验异常（回执数随之 +1，后续基线同步传导）。
            AcceptFulfillmentReqDTO invalidMobile = request("C-MOBILE-INVALID");
            invalidMobile.getTarget().setValues(Map.of(
                    FulfillmentTargetFields.ACCOUNT_TYPE, FulfillmentTargetFields.ACCOUNT_TYPE_MOBILE,
                    FulfillmentTargetFields.ACCOUNT_VALUE, "member-1024"));
            FulfillmentAcceptanceReceiptRespDTO invalidMobileReceipt = facade.acceptFulfillment(invalidMobile);
            assertThat(invalidMobileReceipt.getReceiptStatus())
                    .isEqualTo(FulfillmentReceiptStatus.REJECTED_FINAL);
            assertThat(invalidMobileReceipt.getRejectionCode())
                    .isEqualTo(AcceptanceRejectionCode.INVALID_TARGET.name());

            AcceptFulfillmentReqDTO unknownProject = request("C-PROJECT-MISSING");
            unknownProject.setProjectCode("PRJ_NOT_EXIST");
            assertThatThrownBy(() -> facade.acceptFulfillment(unknownProject))
                    .isInstanceOf(ServiceException.class)
                    .extracting("code").isEqualTo(PROJECT_NOT_FOUND.getCode());
            assertCounts(jdbc, 2, 1, 1, 1, 1, 1);

            AcceptFulfillmentReqDTO invalidQuantity = request("C-QTY");
            invalidQuantity.setQuantity(2L);
            assertThat(facade.acceptFulfillment(invalidQuantity).getReceiptStatus())
                    .isEqualTo(FulfillmentReceiptStatus.REJECTED_FINAL);
            assertThat(rejection(jdbc, "C-QTY")).isEqualTo(AcceptanceRejectionCode.QUANTITY_NOT_SUPPORTED.name());
            assertCounts(jdbc, 3, 1, 1, 1, 1, 1);

            setAvailability(context, ListingAvailability.UNAVAILABLE, "SUPPLY_DOWN");
            assertThat(facade.acceptFulfillment(request("C-SUPPLY")).getReceiptStatus())
                    .isEqualTo(FulfillmentReceiptStatus.REJECTED_RETRYABLE);
            assertThat(rejection(jdbc, "C-SUPPLY")).isEqualTo(AcceptanceRejectionCode.INVENTORY_INSUFFICIENT.name());
            setAvailability(context, ListingAvailability.AVAILABLE, "SUPPLY_READY");

            fixture.supplier().setStatus(SupplierStatus.SUSPENDED.getCode());
            fixture.supplier().setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            context.getBean(SupplierMapper.class).updateById(fixture.supplier());
            assertThat(facade.acceptFulfillment(request("C-ROUTE")).getReceiptStatus())
                    .isEqualTo(FulfillmentReceiptStatus.REJECTED_RETRYABLE);
            assertThat(rejection(jdbc, "C-ROUTE")).isEqualTo(AcceptanceRejectionCode.NO_ROUTABLE_SUPPLIER.name());
            fixture.supplier().setStatus(SupplierStatus.ACTIVE.getCode());
            fixture.supplier().setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            context.getBean(SupplierMapper.class).updateById(fixture.supplier());

            long baselineOrders = count(jdbc, "sc_supply_chain_order");
            long baselineRequests = count(jdbc, "sc_fulfillment_request");
            long baselineReservations = count(jdbc, "sc_fund_reservation");
            long baselineOutbox = count(jdbc, "sc_outbox_event");
            BuyerAccountDO beforeFailures = context.getBean(BuyerAccountMapper.class)
                    .selectByBuyerId(fixture.buyerId());
            for (AcceptanceFailureInjector.Stage stage : AcceptanceFailureInjector.Stage.values()) {
                failures.failAt(stage);
                AcceptFulfillmentReqDTO failed = request("C-FAIL-" + stage.name());
                assertThatThrownBy(() -> facade.acceptFulfillment(failed))
                        .isInstanceOf(InjectedFailure.class);
                failures.clear();
                assertThat(count(jdbc, "sc_supply_chain_order")).isEqualTo(baselineOrders);
                assertThat(count(jdbc, "sc_fulfillment_request")).isEqualTo(baselineRequests);
                assertThat(count(jdbc, "sc_fund_reservation")).isEqualTo(baselineReservations);
                assertThat(count(jdbc, "sc_outbox_event")).isEqualTo(baselineOutbox);
                assertThat(jdbc.queryForObject("SELECT status FROM sc_fulfillment_acceptance_receipt "
                        + "WHERE buyer_order_no=?", Short.class, failed.getBuyerOrderNo())).isZero();
            }
            BuyerAccountDO afterFailures = context.getBean(BuyerAccountMapper.class)
                    .selectByBuyerId(fixture.buyerId());
            assertThat(afterFailures.getBalance()).isEqualTo(beforeFailures.getBalance());
            assertThat(afterFailures.getFrozen()).isEqualTo(beforeFailures.getFrozen());

            // 调用方商品版本已删除：版本过期拒绝的等价触发改为服务端当前发布版本失效（valid_to 已过），
            // 业务版本由服务端在受理事务内锁定校验。
            jdbc.update("UPDATE sc_buyer_listing_version SET valid_to=? WHERE listing_id="
                    + "(SELECT id FROM sc_buyer_listing WHERE listing_no=?) AND version_no=1",
                    LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1), fixture.listingNo());
            AcceptFulfillmentReqDTO oldListing = request("C-LISTING-RETRY");
            FulfillmentAcceptanceReceiptRespDTO listingRejected = facade.acceptFulfillment(oldListing);
            assertThat(listingRejected.getReceiptStatus()).isEqualTo(FulfillmentReceiptStatus.REJECTED_RETRYABLE);
            assertThat(rejection(jdbc, "C-LISTING-RETRY"))
                    .isEqualTo(AcceptanceRejectionCode.LISTING_VERSION_EXPIRED.name());

            AcceptFulfillmentReqDTO alteredTarget = copy(oldListing);
            alteredTarget.setPreviousReceiptVersion(listingRejected.getReceiptVersion());
            alteredTarget.getTarget().setValues(Map.of(
                    FulfillmentTargetFields.ACCOUNT_TYPE, FulfillmentTargetFields.ACCOUNT_TYPE_MOBILE,
                    FulfillmentTargetFields.ACCOUNT_VALUE, "13900139000"));
            assertThatThrownBy(() -> facade.acceptFulfillment(alteredTarget))
                    .isInstanceOf(ServiceException.class);

            BuyerListingPublicationService publication = context.getBean(BuyerListingPublicationService.class);
            publication.publish(new BuyerListingPublicationService.PublishListingCommand(
                    fixture.listingNo(), 1L, 9_700L, "话费直充 100 元",
                    LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1), null, "调价", 1L,
                    "trace-listing-v2", null, null));
            // 可重试拒绝携带当前 CAS 即重开，不再要求调用方更换 Listing 版本。
            AcceptFulfillmentReqDTO corrected = copy(oldListing);
            corrected.setPreviousReceiptVersion(listingRejected.getReceiptVersion());
            FulfillmentAcceptanceReceiptRespDTO retried = facade.acceptFulfillment(corrected);
            assertThat(retried.getReceiptStatus()).isEqualTo(FulfillmentReceiptStatus.ACCEPTED);

            FulfillmentAcceptanceReceiptRespDTO overdraft =
                    facade.acceptFulfillment(request("C-BALANCE"));
            assertThat(overdraft.getReceiptStatus()).isEqualTo(FulfillmentReceiptStatus.ACCEPTED);
            assertThat(count(jdbc, "sc_supply_chain_order")).isEqualTo(3L);
            assertThat(count(jdbc, "sc_fund_reservation")).isEqualTo(3L);

            configureDailyLimit(context, 3L);
            AcceptFulfillmentReqDTO anotherProject = request("C-PROJECT-B");
            anotherProject.setProjectCode(PROJECT_B);
            assertThat(facade.acceptFulfillment(anotherProject).getReceiptStatus())
                    .isEqualTo(FulfillmentReceiptStatus.ACCEPTED);

            AcceptFulfillmentReqDTO otherPhone = request("C-LIMIT-OTHER-PHONE");
            // 购买人身份已删除：每人限购换用户的等价表达改为实际目标手机号。
            otherPhone.setTarget(mobileTarget("13900139000"));
            assertThat(facade.acceptFulfillment(otherPhone).getReceiptStatus())
                    .isEqualTo(FulfillmentReceiptStatus.ACCEPTED);

            AcceptFulfillmentReqDTO afterCancel = request("C-LIMIT-CANCEL");
            assertUserLimitRejected(facade.acceptFulfillment(afterCancel));
            jdbc.update("UPDATE sc_supply_chain_order SET order_status=? WHERE buyer_order_no=?",
                    SupplyChainOrderStatus.CANCELLED.getCode(), "C-CONCURRENT");
            afterCancel.setPreviousReceiptVersion(receiptVersion(jdbc, afterCancel.getBuyerOrderNo()));
            assertThat(facade.acceptFulfillment(afterCancel).getReceiptStatus())
                    .isEqualTo(FulfillmentReceiptStatus.ACCEPTED);

            AcceptFulfillmentReqDTO afterFailure = request("C-LIMIT-FAILURE");
            assertUserLimitRejected(facade.acceptFulfillment(afterFailure));
            jdbc.update("UPDATE sc_supply_chain_order SET order_status=? WHERE buyer_order_no=?",
                    SupplyChainOrderStatus.FAILED.getCode(), "C-LISTING-RETRY");
            afterFailure.setPreviousReceiptVersion(receiptVersion(jdbc, afterFailure.getBuyerOrderNo()));
            assertThat(facade.acceptFulfillment(afterFailure).getReceiptStatus())
                    .isEqualTo(FulfillmentReceiptStatus.ACCEPTED);

            AcceptFulfillmentReqDTO afterRefund = request("C-LIMIT-REFUND");
            assertUserLimitRejected(facade.acceptFulfillment(afterRefund));
            jdbc.update("UPDATE sc_supply_chain_order SET aftersale_status=? WHERE buyer_order_no=?",
                    SupplyChainAftersaleStatus.FULLY_REFUNDED.getCode(), "C-BALANCE");
            afterRefund.setPreviousReceiptVersion(receiptVersion(jdbc, afterRefund.getBuyerOrderNo()));
            assertThat(facade.acceptFulfillment(afterRefund).getReceiptStatus())
                    .isEqualTo(FulfillmentReceiptStatus.ACCEPTED);

            AcceptFulfillmentReqDTO afterPartialRefund = request("C-LIMIT-PARTIAL");
            assertUserLimitRejected(facade.acceptFulfillment(afterPartialRefund));
            jdbc.update("UPDATE sc_supply_chain_order SET aftersale_status=? WHERE buyer_order_no=?",
                    SupplyChainAftersaleStatus.PARTIALLY_REFUNDED.getCode(), "C-LIMIT-CANCEL");
            afterPartialRefund.setPreviousReceiptVersion(
                    receiptVersion(jdbc, afterPartialRefund.getBuyerOrderNo()));
            assertUserLimitRejected(facade.acceptFulfillment(afterPartialRefund));

            configureDailyLimit(context, 4L);
            List<FulfillmentAcceptanceReceiptRespDTO> limitRace = concurrentlyIndexed(10,
                    index -> facade.acceptFulfillment(request("C-LIMIT-RACE-" + index)));
            assertThat(limitRace).filteredOn(response ->
                    response.getReceiptStatus() == FulfillmentReceiptStatus.ACCEPTED).hasSize(1);
            assertThat(limitRace).filteredOn(response ->
                    response.getReceiptStatus() == FulfillmentReceiptStatus.REJECTED_RETRYABLE)
                    .hasSize(9).allSatisfy(FulfillmentAcceptanceIntegrationTest::assertUserLimitRejected);

            configurePhoneBindDailyLimit(context, 1L);
            AcceptFulfillmentReqDTO phoneFirst = request("C-PHONE-BIND-A");
            phoneFirst.setTarget(mobileTarget("13700137000"));
            assertThat(facade.acceptFulfillment(phoneFirst).getReceiptStatus())
                    .isEqualTo(FulfillmentReceiptStatus.ACCEPTED);
            assertThat(jdbc.queryForObject(
                    "SELECT fulfillment_account_value FROM sc_supply_chain_order WHERE buyer_order_no=?",
                    String.class, "C-PHONE-BIND-A")).isEqualTo("13700137000");

            AcceptFulfillmentReqDTO phoneShared = request("C-PHONE-BIND-B");
            phoneShared.setTarget(mobileTarget("13700137000"));
            assertPhoneBindLimitRejected(facade.acceptFulfillment(phoneShared));

            AcceptFulfillmentReqDTO phoneOther = request("C-PHONE-BIND-C");
            phoneOther.setTarget(mobileTarget("13600136000"));
            assertThat(facade.acceptFulfillment(phoneOther).getReceiptStatus())
                    .isEqualTo(FulfillmentReceiptStatus.ACCEPTED);
            assertThat(jdbc.queryForObject(
                    "SELECT fulfillment_account_value FROM sc_supply_chain_order WHERE buyer_order_no=?",
                    String.class, "C-PHONE-BIND-C")).isEqualTo("13600136000");

            AcceptFulfillmentReqDTO notMobile = request("C-PHONE-BIND-EXT");
            FulfillmentTargetDTO external = new FulfillmentTargetDTO();
            external.setValues(Map.of(
                    FulfillmentTargetFields.ACCOUNT_TYPE, FulfillmentTargetFields.ACCOUNT_TYPE_EXTERNAL_ACCOUNT,
                    FulfillmentTargetFields.ACCOUNT_VALUE, "ext-account-1"));
            notMobile.setTarget(external);
            FulfillmentAcceptanceReceiptRespDTO missingMobile = facade.acceptFulfillment(notMobile);
            assertThat(missingMobile.getReceiptStatus()).isEqualTo(FulfillmentReceiptStatus.REJECTED_FINAL);
            assertThat(missingMobile.getRejectionCode())
                    .isEqualTo(AcceptanceRejectionCode.PHONE_BIND_TARGET_REQUIRED.name());

            disablePhoneBindLimit(context);
            jdbc.update("DELETE FROM sc_buyer_account WHERE buyer_id = ?", fixture.buyerId());
            AcceptFulfillmentReqDTO withoutAccount = request("C-NO-ACCOUNT");
            withoutAccount.setProjectCode(PROJECT_B);
            FulfillmentAcceptanceReceiptRespDTO missingAccount = facade.acceptFulfillment(withoutAccount);
            assertThat(missingAccount.getReceiptStatus()).isEqualTo(FulfillmentReceiptStatus.REJECTED_RETRYABLE);
            assertThat(rejection(jdbc, "C-NO-ACCOUNT"))
                    .isEqualTo(AcceptanceRejectionCode.BUYER_ACCOUNT_NOT_OPENED.name());
        }
    }

    @Test
    void supplierSkuDailyQuotaSerializesConcurrentAcceptanceAndReleasesFailedOrder() throws Exception {
        try (ConfigurableApplicationContext context = applicationContext()) {
            Fixture fixture = seed(context);
            PlatformSkuDO sku = context.getBean(PlatformSkuMapper.class).selectByCode("SKU_001_100");
            SupplierSkuDailyQuotaMapper quotaMapper = context.getBean(SupplierSkuDailyQuotaMapper.class);
            LocalDateTime now = context.getBean(com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock.class)
                    .now();
            quotaMapper.insert(SupplierSkuDailyQuotaDO.builder()
                    .supplierId(fixture.supplier().getId()).platformSkuId(sku.getId())
                    .dailyLimit(1L).version(0L).createdBy(1L).createdAt(now)
                    .updatedBy(1L).updatedAt(now).build());

            BuyerFulfillmentCommandFacade facade = context.getBean(BuyerFulfillmentCommandFacade.class);
            List<FulfillmentAcceptanceReceiptRespDTO> concurrent = concurrentlyIndexed(8,
                    index -> facade.acceptFulfillment(request("C-QUOTA-RACE-" + index)));
            assertThat(concurrent).filteredOn(response ->
                    response.getReceiptStatus() == FulfillmentReceiptStatus.ACCEPTED).hasSize(1);
            assertThat(concurrent).filteredOn(response ->
                    response.getReceiptStatus() == FulfillmentReceiptStatus.REJECTED_RETRYABLE)
                    .hasSize(7).allSatisfy(response -> assertThat(response.getRejectionCode())
                            .isEqualTo(AcceptanceRejectionCode.NO_ROUTABLE_SUPPLIER.name()));

            JdbcTemplate jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_supply_chain_order", Long.class))
                    .isEqualTo(1L);
            jdbc.update("UPDATE sc_supply_chain_order SET order_status=?",
                    SupplyChainOrderStatus.FAILED.getCode());
            assertThat(facade.acceptFulfillment(request("C-QUOTA-AFTER-FAIL")).getReceiptStatus())
                    .isEqualTo(FulfillmentReceiptStatus.ACCEPTED);
        }
    }

    @Test
    void bpdWaitsForSkuThenSeesPredecessorCommittedDailyQuota() throws Exception { verifyQuotaVisibility(true); }

    @Test
    void oldAliasWaitsForSkuThenSeesPredecessorCommittedDailyQuota() throws Exception { verifyQuotaVisibility(false); }

    /** 两个不同买家LST共用SKU，后一事务确实等待SKU而非LST；普通读只能在锁后建立RR，见桥接设计§3.1/§13.2。 */
    private void verifyQuotaVisibility(boolean useBpd) throws Exception {
        try (var context = applicationContext(SelectionConfiguration.class)) {
            Fixture fixture = seed(context, true);
            var jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
            var sku = context.getBean(PlatformSkuMapper.class).selectByCode("SKU_001_100");
            var now = context.getBean(com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock.class).now();
            context.getBean(SupplierSkuDailyQuotaMapper.class).insert(SupplierSkuDailyQuotaDO.builder()
                    .supplierId(fixture.supplier().getId()).platformSkuId(sku.getId()).dailyLimit(1L).version(0L)
                    .createdBy(1L).updatedBy(1L).createdAt(now).updatedAt(now).build());
            String buyerCode = context.getBean(BuyerCommandService.class).createBuyer(new BuyerCommandService.CreateBuyerCommand(
                    null, "第二买家", "第二买家", null, null, "AIFULI_MALL", "tenant-next", now.minusMinutes(5), 1L));
            Long buyerId = context.getBean(com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper.class).selectByBuyerCode(buyerCode).getId();
            insertActiveProject(context.getBean(BuyerProjectMapper.class), "PRJ_NEXT", buyerId, now);
            String listingNo = context.getBean(ProductCatalogCommandService.class).createBuyerListing(
                    new ProductCatalogCommandService.CreateBuyerListingCommand(buyerCode, "SKU_001_100", "next-alias", 1L));
            context.getBean(BuyerListingPublicationService.class).publish(new BuyerListingPublicationService.PublishListingCommand(
                    listingNo, 0L, 9800L, "合法手工价格", now.minusMinutes(1), null, "测试", 1L, "trace-next", null, null));
            var listing = context.getBean(BuyerListingMapper.class).selectByListingNo(listingNo);
            var availability = context.getBean(BuyerListingAvailabilityMapper.class).selectByListingId(listing.getId());
            availability.setAvailability(ListingAvailability.AVAILABLE.getCode());
            context.getBean(BuyerListingAvailabilityMapper.class).updateById(availability);
            var selection = context.getBean(com.aifuli.module.supplychain.tradecore.catalog.application.BuyerProductCommandService.class);
            String bpd = selection.addProducts(buyerCode, List.of(new com.aifuli.module.supplychain.tradecore.catalog.application.BuyerProductCommandService.ProductLine(
                    "SKU_001_100", null, null, null, null, null)), 1L).getFirst();

            var reached = new CountDownLatch(1); var release = new CountDownLatch(1); var secondAtSku = new CountDownLatch(1);
            var interceptor = new com.aifuli.module.supplychain.testsupport.DeterministicSqlInterceptor();
            var acquired = new java.util.concurrent.atomic.AtomicBoolean();
            interceptor.use(new com.aifuli.module.supplychain.testsupport.DeterministicSqlInterceptor.Hook() {
                public void before(String statement, String sql, Object params) {
                    if (Thread.currentThread().getName().equals("quota-second") && sql.contains("sc_platform_sku") && sql.toUpperCase().contains("FOR UPDATE")) {
                        secondAtSku.countDown();
                    }
                }
                public void after(String statement, String sql, Object params) throws Exception {
                    if (sql.contains("sc_platform_sku") && sql.toUpperCase().contains("FOR UPDATE")) {
                        if (Thread.currentThread().getName().equals("quota-first") && acquired.compareAndSet(false, true)) {
                            reached.countDown(); com.aifuli.module.supplychain.testsupport.DeterministicSqlInterceptor.await(release);
                        } else if (Thread.currentThread().getName().equals("quota-second")) {
                            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_supply_chain_order WHERE buyer_order_no='QUOTA-FIRST'", Long.class)).isEqualTo(1L);
                        }
                    }
                }
            });
            context.getBean("supplyChainSqlSessionFactory", org.apache.ibatis.session.SqlSessionFactory.class).getConfiguration().addInterceptor(interceptor);
            var facade = context.getBean(BuyerFulfillmentCommandFacade.class);
            var secondRequest = request("QUOTA-SECOND"); secondRequest.setExternalTenantId("tenant-next");
            secondRequest.setProjectCode("PRJ_NEXT"); secondRequest.setBuyerSkuCode(useBpd ? bpd : "next-alias");
            try (var firstExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "quota-first"));
                 var secondExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "quota-second"))) {
                var first = firstExecutor.submit(() -> facade.acceptFulfillment(request("QUOTA-FIRST")));
                try {
                    com.aifuli.module.supplychain.testsupport.DeterministicSqlInterceptor.await(reached);
                    var second = secondExecutor.submit(() -> facade.acceptFulfillment(secondRequest));
                    com.aifuli.module.supplychain.testsupport.DeterministicSqlInterceptor.await(secondAtSku);
                    boolean waiting = false; long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                    try (var connection = java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())) {
                        while (!waiting && System.nanoTime() < deadline) {
                            try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks d ON d.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE d.OBJECT_NAME='sc_platform_sku'")) {
                                rows.next(); waiting = rows.getLong(1) > 0;
                            }
                            if (!waiting) Thread.sleep(20);
                        }
                    }
                    assertThat(waiting).as("必须真实等待SKU当前读锁").isTrue(); release.countDown();
                    assertThat(first.get(20, java.util.concurrent.TimeUnit.SECONDS).getReceiptStatus()).isEqualTo(FulfillmentReceiptStatus.ACCEPTED);
                    var rejected = second.get(20, java.util.concurrent.TimeUnit.SECONDS);
                    assertThat(rejected.getReceiptStatus()).isEqualTo(FulfillmentReceiptStatus.REJECTED_RETRYABLE);
                    assertThat(rejected.getRejectionCode()).isEqualTo(AcceptanceRejectionCode.NO_ROUTABLE_SUPPLIER.name());
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_supply_chain_order", Long.class)).isEqualTo(1L);
                } finally { release.countDown(); interceptor.reset(); }
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({com.aifuli.module.supplychain.tradecore.catalog.application.BuyerProductCommandService.class,
            com.aifuli.module.supplychain.tradecore.catalog.application.BuyerProductSelectionBatchTransactionalWorker.class,
            com.aifuli.module.supplychain.tradecore.catalog.application.BuyerAcceptanceCatalogIdentityWriter.class,
            com.aifuli.module.supplychain.tradecore.catalog.application.BuyerAcceptanceCatalogIdentityProbe.class,
            com.aifuli.module.supplychain.tradecore.catalog.application.SettlementPriceResolver.class})
    static class SelectionConfiguration {
    }

    Fixture seed(ConfigurableApplicationContext context) {
        return seed(context, false);
    }

    private Fixture seed(ConfigurableApplicationContext context, boolean allBuyers) {
        // 普通流程fixture明确建立已核验READY（测试密钥端口仅返回测试专用32字节key），不改变生产初始CLOSED。
        // 真实离线覆盖验收由RechargeFrequencyMigrationIntegrationTest独立执行。
        var gateJdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
        byte[] gateFingerprint = context.getBean(com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyMigrationGateService.class).currentKeyFingerprint();
        gateJdbc.update("UPDATE sc_recharge_frequency_migration_gate SET state='READY',verified_artifact=?,identity_key_fingerprint=?,verified_at=NOW(3) WHERE id=1", "a".repeat(40), gateFingerprint);

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).withNano(0);
        BuyerCommandService buyers = context.getBean(BuyerCommandService.class);
        ProductCatalogCommandService catalog = context.getBean(ProductCatalogCommandService.class);
        buyers.createBuyer(new BuyerCommandService.CreateBuyerCommand(
                null, "吾盛", "吾盛", null, null,
                "AIFULI_MALL", "tenant-wusheng", now.minusMinutes(5), 1L));
        catalog.createBrand(new ProductCatalogCommandService.CreateBrandCommand(
                "BRD_001", "中国移动", null, null, 0, 1L));
        catalog.changeBrandStatus("BRD_001", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createCategory(new ProductCatalogCommandService.CreateCategoryCommand(
                "CAT_001", "话费直充", null, 0, 1L));
        catalog.changeCategoryStatus("CAT_001", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSpu(new ProductCatalogCommandService.CreateSpuCommand(
                "SPU_001", "BRD_001", "CAT_001",
                "EQUITY", "EQUITY_PRODUCT", "VIRTUAL_CARD",
                "话费直充", null, null, "中台受理后不可自动退款或撤销", 1L));
        catalog.changeSpuStatus("SPU_001", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSku(new ProductCatalogCommandService.CreateSkuCommand(
                "SKU_001_100", "SPU_001", "话费直充 100 元", FulfillmentType.DIRECT_RECHARGE.code(),
                10_000L, Money.CURRENCY_CNY, 1L));
        catalog.changeSkuStatus("SKU_001_100", CatalogEntityStatus.ACTIVE, 1L);
        String listingNo = catalog.createBuyerListing(
                new ProductCatalogCommandService.CreateBuyerListingCommand(
                        "BYR_001", "SKU_001_100", "WS-TELCO-100", 1L));
        context.getBean(BuyerListingPublicationService.class).publish(
                new BuyerListingPublicationService.PublishListingCommand(
                        listingNo, 0L, 9_800L, "话费直充 100 元", now.minusMinutes(1), null,
                        "首次发布", 1L, "trace-listing", null, null));
        setAvailability(context, ListingAvailability.AVAILABLE, "SUPPLY_READY");

        Long buyerId = context.getBean(com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper.class)
                .selectByBuyerCode("BYR_001").getId();
        insertActiveProject(context.getBean(BuyerProjectMapper.class), PROJECT_A, buyerId, now);
        insertActiveProject(context.getBean(BuyerProjectMapper.class), PROJECT_B, buyerId, now);
        context.getBean(BuyerAccountOpeningService.class).open(
                new BuyerAccountOpeningService.OpeningBalanceCommand(
                        "BYR_001", Money.cny(25_000L), 1L, "evidence://opening",
                        new byte[32], "M1 受理集成测试期初额",
                        "trace-account", null));

        PlatformSkuDO sku = context.getBean(PlatformSkuMapper.class).selectByCode("SKU_001_100");
        SupplierDO supplier = createQnmOffer(context, sku, now);
        // 044 预置引导策略占用同类型唯一 active 位；受理链路种子发布自有 CARD_PRIMARY 策略，先停用预置。
        RoutingPolicyPresetFixture.deactivatePresetPolicy(new JdbcTemplate(
                context.getBean("supplyChainDataSource", DataSource.class)));
        RoutingPolicyPublicationService routing = context.getBean(RoutingPolicyPublicationService.class);
        // PRD 分销商+商品类型层级：两个项目共用 FIXED 主供应商 QINAMENG。
        RoutingPolicyPublicationService.DraftResult draft = routing.createDraft(
                new RoutingPolicyPublicationService.CreateDraftCommand(
                        "CARD_PRIMARY", "卡券与直充主路由", "M1 受理路由",
                        now.minusHours(1), now.plusDays(1), List.of(
                        new RoutingPolicyPublicationService.RuleDraft(
                                "WS_TELCO", "吾盛话费路由", "FIXED", "QINAMENG",
                                allBuyers ? "ALL" : "SPECIFIED", allBuyers ? List.of() : List.of("BYR_001"),
                                "ALL", List.of(),
                                "SPECIFIED", List.of("EQUITY_PRODUCT"),
                                "ALL", List.of(),
                                null, "STABLE", "PRICE", "COMPOSITE",
                                10, (short) 10, false, List.of())), 1L, 40, 30, 30));
        routing.publish(new RoutingPolicyPublicationService.PublishCommand(
                draft.policyVersionId(), 0L, "M1 受理主路由", 1L,
                "trace-routing", null, null));
        return new Fixture(buyerId, listingNo, supplier);
    }

    private SupplierDO createQnmOffer(ConfigurableApplicationContext context,
                                      PlatformSkuDO sku, LocalDateTime now) {
        SupplierMapper supplierMapper = context.getBean(SupplierMapper.class);
        SupplierDO supplier = SupplierDO.builder().supplierCode("QINAMENG").supplierName("奇那梦")
                .shortName("奇那梦").supplierType("STANDARD_SUPPLIER")
                .status(SupplierStatus.ACTIVE.getCode()).version(0L)
                .createdBy(1L).createdAt(now).updatedBy(1L).updatedAt(now).build();
        supplierMapper.insert(supplier);
        CredentialSlotDO credential = CredentialSlotDO.builder().slotNo("CRD_QNM")
                .providerType("ENV").secretRef("env:QNM_CREDENTIAL")
                .maskedHint("qnm-****").activeSecretVersion("v1").status((short) 10).version(0L)
                .createdBy(1L).createdAt(now).updatedBy(1L).updatedAt(now).build();
        context.getBean(CredentialSlotMapper.class).insert(credential);
        SupplierConnectorDO connector = SupplierConnectorDO.builder().connectorNo("CON_QNM")
                .supplierId(supplier.getId()).connectorCode("GAME_ORDER")
                .adapterType("QNM_GAME_ORDER_V1").status((short) 10).version(0L)
                .createdBy(1L).createdAt(now).updatedBy(1L).updatedAt(now).build();
        SupplierConnectorMapper connectorMapper = context.getBean(SupplierConnectorMapper.class);
        connectorMapper.insert(connector);
        SupplierConnectorVersionDO connectorVersion = SupplierConnectorVersionDO.builder()
                .connectorId(connector.getId()).versionNo(1).baseUrl("https://qnm.invalid")
                .merchantAccount("fixture").credentialSlotId(credential.getId()).protocolVersion("V1")
                .callbackPath("/callback").connectTimeoutMs(1000).readTimeoutMs(3000)
                .rateLimit(10).concurrencyLimit(2).adapterConfigSchemaVersion(1)
                .adapterConfigJson("{}").validationStatus((short) 10).validatedAt(now)
                .validationExpiresAt(now.plusDays(1)).publishedAt(now).configDigest(new byte[32])
                .status(ConnectorVersionStatus.PUBLISHED.getCode()).version(0L)
                .createdBy(1L).createdAt(now).updatedBy(1L).updatedAt(now).build();
        context.getBean(SupplierConnectorVersionMapper.class).insert(connectorVersion);
        // 共享结构有效性守卫要求主能力及执行画像所需查询/回调能力启用（生命周期设计 §3.2）。
        SupplierConnectorCapabilityMapper capabilityMapper = context.getBean(SupplierConnectorCapabilityMapper.class);
        for (String code : List.of("CREATE_RECHARGE", "QUERY_ORDER", "RECEIVE_CALLBACK")) {
            capabilityMapper.insert(SupplierConnectorCapabilityDO.builder()
                    .connectorVersionId(connectorVersion.getId()).capabilityCode(code)
                    .enabled((short) 1).metadataVersion(1).createdAt(now).build());
        }
        connector.setCurrentVersionId(connectorVersion.getId());
        connector.setUpdatedAt(now);
        connectorMapper.updateById(connector);
        InventorySourceDO inventory = InventorySourceDO.builder().sourceNo("INV_QNM")
                .supplierId(supplier.getId()).platformSkuId(sku.getId())
                .sourceType("REMOTE_ON_DEMAND").externalSourceCode("QNM_REMOTE")
                .ownerType("SUPPLIER").status(InventorySourceStatus.ACTIVE.getCode()).version(0L)
                .createdBy(1L).createdAt(now).updatedBy(1L).updatedAt(now).build();
        context.getBean(InventorySourceMapper.class).insert(inventory);
        SupplierOfferDO offer = SupplierOfferDO.builder().offerNo("OFR_QNM_TELCO")
                .offerCode("SKU_001_100").supplierId(supplier.getId()).platformSkuId(sku.getId())
                .status(OfferStatus.PUBLISHED.getCode()).version(0L)
                .createdBy(1L).createdAt(now).updatedBy(1L).updatedAt(now).build();
        SupplierOfferMapper offerMapper = context.getBean(SupplierOfferMapper.class);
        offerMapper.insert(offer);
        SupplierOfferVersionDO version = SupplierOfferVersionDO.builder().offerId(offer.getId())
                .versionNo(1).externalSkuCode("QNM-TELCO-100").supplierSettlementUnitPrice(9_000L)
                .currency("CNY").connectorVersionId(connectorVersion.getId())
                .inventorySourceId(inventory.getId()).minQuantity(1L).maxQuantity(1L).quantityStep(1L)
                .primaryCapabilityCode("CREATE_RECHARGE").completionMode("QUERY_OR_CALLBACK")
                .createIdempotencyMode("DEDUP_ONLY").unknownResultPolicy("QUERY_ORDER")
                .partialDeliveryMode("FORBIDDEN").resultMultiplicity("NONE")
                .fulfillmentConfigSchemaVersion(1).fulfillmentConfigJson("{}")
                .validFrom(now.minusHours(1)).publishReason("M1 奇那梦联调")
                .publishedBy(1L).publishedAt(now).createdAt(now).build();
        context.getBean(SupplierOfferVersionMapper.class).insert(version);
        offer.setCurrentVersionId(version.getId());
        offer.setUpdatedAt(now);
        offerMapper.updateById(offer);
        return supplier;
    }

    private static void setAvailability(ConfigurableApplicationContext context,
                                        ListingAvailability value, String reason) {
        BuyerListingMapper listingMapper = context.getBean(BuyerListingMapper.class);
        BuyerListingDO listing = listingMapper.selectByBuyerAndSkuCode(
                context.getBean(com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper.class)
                        .selectByBuyerCode("BYR_001").getId(), "WS-TELCO-100");
        BuyerListingAvailabilityMapper mapper = context.getBean(BuyerListingAvailabilityMapper.class);
        BuyerListingAvailabilityDO availability = mapper.selectByListingId(listing.getId());
        availability.setAvailability(value.getCode());
        availability.setReasonCode(reason);
        availability.setEvaluatedAt(LocalDateTime.now(ZoneOffset.UTC));
        availability.setBasisVersion(availability.getBasisVersion() + 1);
        availability.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        assertThat(mapper.updateById(availability)).isEqualTo(1);
    }

    static AcceptFulfillmentReqDTO request(String buyerOrderNo) {
        AcceptFulfillmentReqDTO request = new AcceptFulfillmentReqDTO();
        request.setSourceSystem("AIFULI_MALL");
        request.setExternalTenantId("tenant-wusheng");
        request.setProjectCode(PROJECT_A);
        request.setBuyerOrderNo(buyerOrderNo);
        request.setBuyerSkuCode("WS-TELCO-100");
        request.setQuantity(1L);
        request.setTarget(mobileTarget("13800138000"));
        return request;
    }

    private static FulfillmentTargetDTO mobileTarget(String accountValue) {
        FulfillmentTargetDTO target = new FulfillmentTargetDTO();
        target.setValues(Map.of(
                FulfillmentTargetFields.ACCOUNT_TYPE, FulfillmentTargetFields.ACCOUNT_TYPE_MOBILE,
                FulfillmentTargetFields.ACCOUNT_VALUE, accountValue));
        return target;
    }

    private static AcceptFulfillmentReqDTO copy(AcceptFulfillmentReqDTO source) {
        AcceptFulfillmentReqDTO target = new AcceptFulfillmentReqDTO();
        target.setSourceSystem(source.getSourceSystem());
        target.setExternalTenantId(source.getExternalTenantId());
        target.setProjectCode(source.getProjectCode());
        target.setBuyerOrderNo(source.getBuyerOrderNo());
        target.setBuyerSkuCode(source.getBuyerSkuCode());
        target.setQuantity(source.getQuantity());
        target.setPreviousReceiptVersion(source.getPreviousReceiptVersion());
        FulfillmentTargetDTO fulfillmentTarget = new FulfillmentTargetDTO();
        fulfillmentTarget.setValues(Map.copyOf(source.getTarget().getValues()));
        target.setTarget(fulfillmentTarget);
        return target;
    }

    private static List<FulfillmentAcceptanceReceiptRespDTO> concurrently(
            int count, ThrowingSupplier<FulfillmentAcceptanceReceiptRespDTO> supplier) throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(count)) {
            CountDownLatch ready = new CountDownLatch(count);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<FulfillmentAcceptanceReceiptRespDTO>> futures = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return supplier.get();
                }));
            }
            ready.await();
            start.countDown();
            List<FulfillmentAcceptanceReceiptRespDTO> results = new ArrayList<>();
            for (Future<FulfillmentAcceptanceReceiptRespDTO> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    private static List<FulfillmentAcceptanceReceiptRespDTO> concurrentlyIndexed(
            int count, IndexedThrowingSupplier<FulfillmentAcceptanceReceiptRespDTO> supplier) throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(count)) {
            CountDownLatch ready = new CountDownLatch(count);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<FulfillmentAcceptanceReceiptRespDTO>> futures = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                int current = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return supplier.get(current);
                }));
            }
            ready.await();
            start.countDown();
            List<FulfillmentAcceptanceReceiptRespDTO> results = new ArrayList<>();
            for (Future<FulfillmentAcceptanceReceiptRespDTO> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    private static void assertCounts(JdbcTemplate jdbc, long receipts, long orders, long requests,
                                     long rounds, long bOrders, long operations) {
        assertThat(count(jdbc, "sc_fulfillment_acceptance_receipt")).isEqualTo(receipts);
        assertThat(count(jdbc, "sc_supply_chain_order")).isEqualTo(orders);
        assertThat(count(jdbc, "sc_fulfillment_request")).isEqualTo(requests);
        assertThat(count(jdbc, "sc_supplier_routing_round")).isEqualTo(rounds);
        assertThat(count(jdbc, "sc_b_order")).isEqualTo(bOrders);
        assertThat(count(jdbc, "sc_fulfillment_operation")).isEqualTo(operations);
        var orderNumbers = jdbc.queryForList("SELECT order_no FROM sc_supply_chain_order", String.class);
        var billNumbers = jdbc.queryForList("SELECT billno FROM sc_b_order", String.class);
        assertThat(orderNumbers).allSatisfy(value -> assertThat(value).matches("[0-9]{24}"));
        assertThat(billNumbers).allSatisfy(value -> assertThat(value).matches("[0-9]{24}"));
        var allNumbers = new ArrayList<>(orderNumbers); allNumbers.addAll(billNumbers);
        assertThat(allNumbers).doesNotHaveDuplicates();
    }

    private static long count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private static String rejection(JdbcTemplate jdbc, String buyerOrderNo) {
        return jdbc.queryForObject("SELECT rejection_code FROM sc_fulfillment_acceptance_receipt "
                + "WHERE buyer_order_no=?", String.class, buyerOrderNo);
    }

    private static long receiptVersion(JdbcTemplate jdbc, String buyerOrderNo) {
        return jdbc.queryForObject("SELECT version FROM sc_fulfillment_acceptance_receipt "
                + "WHERE buyer_order_no=?", Long.class, buyerOrderNo);
    }

    private static void assertUserLimitRejected(FulfillmentAcceptanceReceiptRespDTO response) {
        assertThat(response.getReceiptStatus()).isEqualTo(FulfillmentReceiptStatus.REJECTED_RETRYABLE);
        assertThat(response.getRejectionCode())
                .isEqualTo(AcceptanceRejectionCode.USER_PURCHASE_LIMIT_EXCEEDED.name());
    }

    private static void assertPhoneBindLimitRejected(FulfillmentAcceptanceReceiptRespDTO response) {
        assertThat(response.getReceiptStatus()).isEqualTo(FulfillmentReceiptStatus.REJECTED_RETRYABLE);
        assertThat(response.getRejectionCode())
                .isEqualTo(AcceptanceRejectionCode.PHONE_BIND_PURCHASE_LIMIT_EXCEEDED.name());
    }

    private static void configureDailyLimit(ConfigurableApplicationContext context, long quantity) {
        PlatformSkuMapper mapper = context.getBean(PlatformSkuMapper.class);
        PlatformSkuDO sku = mapper.selectByCode("SKU_001_100");
        sku.setUserLimitEnabled(true);
        sku.setUserLimitPeriod("DAY");
        sku.setUserLimitQuantity(quantity);
        sku.setOrderLimitEnabled(true);
        assertThat(mapper.updateById(sku)).isEqualTo(1);
    }

    private static void configurePhoneBindDailyLimit(ConfigurableApplicationContext context, long quantity) {
        PlatformSkuMapper mapper = context.getBean(PlatformSkuMapper.class);
        PlatformSkuDO sku = mapper.selectByCode("SKU_001_100");
        sku.setUserLimitEnabled(false);
        sku.setUserLimitPeriod(null);
        sku.setUserLimitQuantity(null);
        sku.setPhoneBindLimitEnabled(true);
        sku.setPhoneBindLimitPeriod("DAY");
        sku.setPhoneBindLimitQuantity(quantity);
        assertThat(mapper.updateById(sku)).isEqualTo(1);
    }

    private static void disablePhoneBindLimit(ConfigurableApplicationContext context) {
        PlatformSkuMapper mapper = context.getBean(PlatformSkuMapper.class);
        PlatformSkuDO sku = mapper.selectByCode("SKU_001_100");
        sku.setPhoneBindLimitEnabled(false);
        sku.setPhoneBindLimitPeriod(null);
        sku.setPhoneBindLimitQuantity(null);
        assertThat(mapper.updateById(sku)).isEqualTo(1);
    }

    private static void insertActiveProject(BuyerProjectMapper mapper, String projectCode,
                                            Long buyerId, LocalDateTime now) {
        BuyerProjectDO project = BuyerProjectDO.builder()
                .projectCode(projectCode)
                .projectName(projectCode)
                .buyerId(buyerId)
                .catalogMode("MANAGED")
                .status(BuyerProjectStatus.ACTIVE.getCode())
                .version(0L)
                .createdBy(1L)
                .createdAt(now)
                .updatedBy(1L)
                .updatedAt(now)
                .build();
        assertThat(mapper.insert(project)).isEqualTo(1);
    }

    ConfigurableApplicationContext applicationContext(Class<?>... additions) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("aifuli.supply-chain.enabled", true);
        // 结构有效性守卫 fail-closed：测试种子连接器必须显式进入白名单（生命周期设计 §3.2）。
        properties.put("aifuli.supply-chain.connector.allowed-adapter-types", "QNM_GAME_ORDER_V1");
        properties.put("aifuli.supply-chain.datasource.url", jdbcUrl());
        properties.put("aifuli.supply-chain.datasource.username", username());
        properties.put("aifuli.supply-chain.datasource.password", password());
        properties.put("aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        // 最终受理新增独立新RR视图Reader（REQUIRES_NEW）：10并发任务各需外层+Reader两个连接。
        properties.put("aifuli.supply-chain.security.key-version", "test-v1");
        properties.put("aifuli.supply-chain.security.envelope-key-base64", KEY_BASE64);
        properties.put("aifuli.supply-chain.security.fingerprint-key-base64", KEY_BASE64);
        return new SpringApplicationBuilder(TestConfiguration.class).sources(additions)
                .web(WebApplicationType.NONE).properties(properties).run();
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
    @Import({com.aifuli.module.supplychain.testsupport.OrderNoTestConfiguration.class, SupplyChainDataSourceConfiguration.class, UlidGenerator.class, BusinessNoGenerator.class,
            OutboxEventRepository.class, BuyerCommandService.class, BuyerResolver.class,
            BuyerProjectAccessResolver.class,
            ProductCatalogCommandService.class, ProductCenterTestConfiguration.class, BuyerListingPublicationService.class,
            BuyerAccountOpeningService.class, FundReservationService.class, AccountLedgerWriter.class,
            RoutingPolicyPublicationService.class, SupplierRoutingApplicationService.class,
            com.aifuli.module.supplychain.tradecore.catalog.application.CurrentSupplyEligibilityReader.class,
            com.aifuli.module.supplychain.infrastructure.configuration.BuyerSelectionCatalogProperties.class,
            com.aifuli.module.supplychain.border.fulfillment.application.FrozenAcceptanceIdentityReader.class,
            com.aifuli.module.supplychain.tradecore.catalog.application.BuyerSelectionCatalogRequestCoordinator.class,
            com.aifuli.module.supplychain.tradecore.catalog.application.BuyerSelectionCatalogRefreshService.class,
            com.aifuli.module.supplychain.tradecore.catalog.application.BuyerSelectionCatalogRefreshTransactionalWorker.class,
            ClosedJsonSchemaValidator.class, FulfillmentTargetSchemaRegistry.class,
            AesGcmEnvelopeCrypto.class, SkuPurchaseLimitService.class, FulfillmentAcceptanceLifecycle.class,
            com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyLifecycle.class,
            com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyMigrationGateService.class,
            com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyIdentityService.class,
            com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyAcceptanceCoordinator.class,
            com.aifuli.module.supplychain.tradecore.catalog.application.RechargeFrequencyProductResolver.class,
            OrderSummaryPolicyRegistry.class, CardOrderSummaryPolicy.class,
            BuyerFulfillmentCommandFacade.class,
            RechargeFrequencyExecutionIntegrationTest.ExecutionConfiguration.class,
            com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyWakeScheduler.class,
            com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyTimerStartupCheck.class,
            com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyRecoveryService.class,
            com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyWakeHandler.class,
            com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyQueryService.class,
            com.aifuli.module.supplychain.border.fulfillment.application.StandardOrderIdentityMigrationService.class,
            com.aifuli.module.supplychain.infrastructure.timer.DatabasePersistentTimer.class,
            com.aifuli.module.supplychain.infrastructure.timer.PersistentTimerHandlerRegistry.class})
    static class TestConfiguration {
        @Bean
        com.aifuli.module.supplychain.infrastructure.security.RechargeFrequencyIdentityKeyProvider frequencyKey() {
            return () -> new byte[32];
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        MutableFailureInjector failureInjector() {
            return new MutableFailureInjector();
        }

        /**
         * 既有履约集成测试聚焦受理原子性，报价签发/绑定由独立定价测试覆盖；这里冻结与原目录价等价的快照，
         * 按项目永久键（无 token、无用户、无支付时间）供受理消费。
         * 设计见 {@code docs/superpowers/specs/2026-10-02-standard-internal-quote-freeze-design.md#3-受理内查取与唯一消费}。
         */
        @Bean
        StandardPricingQuoteService pricingQuoteService() {
            StandardPricingQuoteService service = mock(StandardPricingQuoteService.class);
            when(service.requireForProject(any(ProjectQuoteKeyContext.class)))
                    .thenAnswer(invocation -> {
                        ProjectQuoteKeyContext context = invocation.getArgument(0);
                        long quantity = context.quantity();
                        return new FrozenQuote(1L, 1L, 3L, 9_800L, 10_000L,
                                Math.multiplyExact(10_000L, quantity), 0L,
                                Math.multiplyExact(10_000L, quantity),
                                "{\"taxIncluded\":false,\"taxRateCode\":\"NON_TAXABLE\",\"taxRateLabel\":\"不征税\"}",
                                false, null);
                    });
            return service;
        }

        @Bean
        com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService selectionPricing() {
            // 目录刷新门面的单BPD重算依赖；本测试聚焦受理原子性，重算路径不在此触发。
            return mock(com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService.class);
        }
    }

    static final class MutableFailureInjector implements AcceptanceFailureInjector {
        private volatile Stage stage;

        void failAt(Stage value) {
            stage = value;
        }

        void clear() {
            stage = null;
        }

        @Override
        public void after(Stage current) {
            if (current == stage) {
                throw new InjectedFailure(current);
            }
        }
    }

    static final class InjectedFailure extends RuntimeException {
        InjectedFailure(AcceptanceFailureInjector.Stage stage) {
            super("Injected acceptance failure after " + stage);
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    private interface IndexedThrowingSupplier<T> {
        T get(int index) throws Exception;
    }

    record Fixture(Long buyerId, String listingNo, SupplierDO supplier) {
    }
}
