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
import com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceRejectionCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOutboxCodes;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTargetFields;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTargetSchemaCode;
import com.aifuli.module.supplychain.border.order.domain.CardOrderSummaryPolicy;
import com.aifuli.module.supplychain.border.order.domain.OrderSummaryPolicyRegistry;
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
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerResolver;
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
import com.aifuli.module.supplychain.tradecore.catalog.domain.ListingAvailability;
import com.aifuli.module.supplychain.tradecore.ledger.application.AccountLedgerWriter;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialSlotDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.InventorySourceDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialSlotMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.InventorySourceMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.ConnectorVersionStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.InventorySourceStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
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
import com.aifuli.module.supplychain.testsupport.ProductCenterTestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
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

class FulfillmentAcceptanceIntegrationTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain").withUsername("supply_chain").withPassword("supply_chain");
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
    void acceptsAtomicallyWithPermanentIdempotencyAndRollsBackEveryInjectedStage() throws Exception {
        try (ConfigurableApplicationContext context = applicationContext()) {
            Fixture fixture = seed(context);
            BuyerFulfillmentCommandFacade facade = context.getBean(BuyerFulfillmentCommandFacade.class);
            MutableFailureInjector failures = context.getBean(MutableFailureInjector.class);
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));

            AcceptFulfillmentReqDTO first = request("C-CONCURRENT", 1L);
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
            assertThat(jdbc.queryForObject("SELECT detail_version FROM sc_supply_chain_order", Long.class))
                    .isEqualTo(1L);

            AcceptFulfillmentReqDTO conflicting = copy(first);
            conflicting.setBuyerUserId("another-user");
            assertThatThrownBy(() -> facade.acceptFulfillment(conflicting))
                    .isInstanceOf(ServiceException.class)
                    .extracting("code").isEqualTo(1_050_001_012);
            assertCounts(jdbc, 1, 1, 1, 1, 1, 1);

            AcceptFulfillmentReqDTO invalidQuantity = request("C-QTY", 1L);
            invalidQuantity.setQuantity(2L);
            assertThat(facade.acceptFulfillment(invalidQuantity).getReceiptStatus())
                    .isEqualTo(FulfillmentReceiptStatus.REJECTED_FINAL);
            assertThat(rejection(jdbc, "C-QTY")).isEqualTo(AcceptanceRejectionCode.QUANTITY_NOT_SUPPORTED.name());
            assertCounts(jdbc, 2, 1, 1, 1, 1, 1);

            setAvailability(context, ListingAvailability.UNAVAILABLE, "SUPPLY_DOWN");
            assertThat(facade.acceptFulfillment(request("C-SUPPLY", 1L)).getReceiptStatus())
                    .isEqualTo(FulfillmentReceiptStatus.REJECTED_RETRYABLE);
            assertThat(rejection(jdbc, "C-SUPPLY")).isEqualTo(AcceptanceRejectionCode.INVENTORY_INSUFFICIENT.name());
            setAvailability(context, ListingAvailability.AVAILABLE, "SUPPLY_READY");

            fixture.supplier().setStatus(SupplierStatus.SUSPENDED.getCode());
            fixture.supplier().setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            context.getBean(SupplierMapper.class).updateById(fixture.supplier());
            assertThat(facade.acceptFulfillment(request("C-ROUTE", 1L)).getReceiptStatus())
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
                AcceptFulfillmentReqDTO failed = request("C-FAIL-" + stage.name(), 1L);
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

            AcceptFulfillmentReqDTO oldListing = request("C-LISTING-RETRY", 99L);
            FulfillmentAcceptanceReceiptRespDTO listingRejected = facade.acceptFulfillment(oldListing);
            assertThat(listingRejected.getReceiptStatus()).isEqualTo(FulfillmentReceiptStatus.REJECTED_RETRYABLE);
            assertThat(rejection(jdbc, "C-LISTING-RETRY"))
                    .isEqualTo(AcceptanceRejectionCode.LISTING_VERSION_EXPIRED.name());

            AcceptFulfillmentReqDTO alteredTarget = copy(oldListing);
            alteredTarget.setListingVersion(2L);
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
            AcceptFulfillmentReqDTO corrected = copy(oldListing);
            corrected.setListingVersion(2L);
            corrected.setPreviousReceiptVersion(listingRejected.getReceiptVersion());
            FulfillmentAcceptanceReceiptRespDTO retried = facade.acceptFulfillment(corrected);
            assertThat(retried.getReceiptStatus()).isEqualTo(FulfillmentReceiptStatus.ACCEPTED);

            FulfillmentAcceptanceReceiptRespDTO overdraft =
                    facade.acceptFulfillment(request("C-BALANCE", 2L));
            assertThat(overdraft.getReceiptStatus()).isEqualTo(FulfillmentReceiptStatus.ACCEPTED);
            assertThat(count(jdbc, "sc_supply_chain_order")).isEqualTo(3L);
            assertThat(count(jdbc, "sc_fund_reservation")).isEqualTo(3L);

            jdbc.update("DELETE FROM sc_buyer_account WHERE buyer_id = ?", fixture.buyerId());
            FulfillmentAcceptanceReceiptRespDTO missingAccount =
                    facade.acceptFulfillment(request("C-NO-ACCOUNT", 2L));
            assertThat(missingAccount.getReceiptStatus()).isEqualTo(FulfillmentReceiptStatus.REJECTED_RETRYABLE);
            assertThat(rejection(jdbc, "C-NO-ACCOUNT"))
                    .isEqualTo(AcceptanceRejectionCode.BUYER_ACCOUNT_NOT_OPENED.name());
        }
    }

    private Fixture seed(ConfigurableApplicationContext context) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).withNano(0);
        BuyerCommandService buyers = context.getBean(BuyerCommandService.class);
        ProductCatalogCommandService catalog = context.getBean(ProductCatalogCommandService.class);
        buyers.createBuyer(new BuyerCommandService.CreateBuyerCommand(
                "WUSHENG", "吾盛", "吾盛", null, null,
                "AIFULI_MALL", "tenant-wusheng", now.minusMinutes(5), 1L));
        catalog.createBrand(new ProductCatalogCommandService.CreateBrandCommand(
                "CMCC", "中国移动", null, null, 0, 1L));
        catalog.changeBrandStatus("CMCC", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createCategory(new ProductCatalogCommandService.CreateCategoryCommand(
                FulfillmentType.DIRECT_RECHARGE.code(), "话费直充", null, 0, 1L));
        catalog.changeCategoryStatus(FulfillmentType.DIRECT_RECHARGE.code(), CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSpu(new ProductCatalogCommandService.CreateSpuCommand(
                "TELCO_RECHARGE", "CMCC", FulfillmentType.DIRECT_RECHARGE.code(),
                "EQUITY", "EQUITY_PRODUCT", "VIRTUAL_CARD",
                "话费直充", null, null, "中台受理后不可自动退款或撤销", 1L));
        catalog.changeSpuStatus("TELCO_RECHARGE", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSku(new ProductCatalogCommandService.CreateSkuCommand(
                "TELCO_100", "TELCO_RECHARGE", "话费直充 100 元", FulfillmentType.DIRECT_RECHARGE.code(),
                10_000L, Money.CURRENCY_CNY, FulfillmentTargetSchemaCode.DIRECT_RECHARGE_TARGET.name(), 1,
                "DIRECT_RECHARGE_RESULT", 1, 1L));
        catalog.changeSkuStatus("TELCO_100", CatalogEntityStatus.ACTIVE, 1L);
        String listingNo = catalog.createBuyerListing(
                new ProductCatalogCommandService.CreateBuyerListingCommand(
                        "WUSHENG", "TELCO_100", "WS-TELCO-100", 1L));
        context.getBean(BuyerListingPublicationService.class).publish(
                new BuyerListingPublicationService.PublishListingCommand(
                        listingNo, 0L, 9_800L, "话费直充 100 元", now.minusMinutes(1), null,
                        "首次发布", 1L, "trace-listing", null, null));
        setAvailability(context, ListingAvailability.AVAILABLE, "SUPPLY_READY");

        Long buyerId = context.getBean(com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper.class)
                .selectByBuyerCode("WUSHENG").getId();
        context.getBean(BuyerAccountOpeningService.class).open(
                new BuyerAccountOpeningService.OpeningBalanceCommand(
                        "WUSHENG", Money.cny(25_000L), 1L, "evidence://opening",
                        new byte[32], "M1 受理集成测试期初额",
                        "trace-account", null));

        PlatformSkuDO sku = context.getBean(PlatformSkuMapper.class).selectByCode("TELCO_100");
        SupplierDO supplier = createQnmOffer(context, sku, now);
        RoutingPolicyPublicationService routing = context.getBean(RoutingPolicyPublicationService.class);
        RoutingPolicyPublicationService.DraftResult draft = routing.createDraft(
                new RoutingPolicyPublicationService.CreateDraftCommand(
                        "CARD_PRIMARY", "卡券与直充主路由", "M1 受理路由",
                        now.minusHours(1), now.plusDays(1), List.of(
                        new RoutingPolicyPublicationService.RuleDraft(
                                "WS_TELCO", "吾盛话费路由", "WUSHENG", "TELCO_100",
                                FulfillmentType.DIRECT_RECHARGE.code(), 10, List.of(
                                new RoutingPolicyPublicationService.CandidateDraft(
                                        "QINAMENG", 10, "UNSUPPORTED", null,
                                        "UNSUPPORTED", null, "UNSUPPORTED", null)))), 1L));
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
                .offerCode("TELCO_100").supplierId(supplier.getId()).platformSkuId(sku.getId())
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
                        .selectByBuyerCode("WUSHENG").getId(), "WS-TELCO-100");
        BuyerListingAvailabilityMapper mapper = context.getBean(BuyerListingAvailabilityMapper.class);
        BuyerListingAvailabilityDO availability = mapper.selectByListingId(listing.getId());
        availability.setAvailability(value.getCode());
        availability.setReasonCode(reason);
        availability.setEvaluatedAt(LocalDateTime.now(ZoneOffset.UTC));
        availability.setBasisVersion(availability.getBasisVersion() + 1);
        availability.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        assertThat(mapper.updateById(availability)).isEqualTo(1);
    }

    private static AcceptFulfillmentReqDTO request(String buyerOrderNo, long listingVersion) {
        AcceptFulfillmentReqDTO request = new AcceptFulfillmentReqDTO();
        request.setSourceSystem("AIFULI_MALL");
        request.setExternalTenantId("tenant-wusheng");
        request.setBuyerOrderNo(buyerOrderNo);
        request.setBuyerUserId("member-1024");
        request.setBuyerSkuCode("WS-TELCO-100");
        request.setListingVersion(listingVersion);
        request.setQuantity(1L);
        request.setPaidAt(OffsetDateTime.now(ZoneOffset.UTC));
        FulfillmentTargetDTO target = new FulfillmentTargetDTO();
        target.setSchemaCode(FulfillmentTargetSchemaCode.DIRECT_RECHARGE_TARGET.name());
        target.setSchemaVersion(1);
        target.setValues(Map.of(
                FulfillmentTargetFields.ACCOUNT_TYPE, FulfillmentTargetFields.ACCOUNT_TYPE_MOBILE,
                FulfillmentTargetFields.ACCOUNT_VALUE, "13800138000"));
        request.setTarget(target);
        return request;
    }

    private static AcceptFulfillmentReqDTO copy(AcceptFulfillmentReqDTO source) {
        AcceptFulfillmentReqDTO target = new AcceptFulfillmentReqDTO();
        target.setSourceSystem(source.getSourceSystem());
        target.setExternalTenantId(source.getExternalTenantId());
        target.setBuyerOrderNo(source.getBuyerOrderNo());
        target.setBuyerUserId(source.getBuyerUserId());
        target.setBuyerSkuCode(source.getBuyerSkuCode());
        target.setListingVersion(source.getListingVersion());
        target.setQuantity(source.getQuantity());
        target.setPaidAt(source.getPaidAt());
        target.setPreviousReceiptVersion(source.getPreviousReceiptVersion());
        FulfillmentTargetDTO fulfillmentTarget = new FulfillmentTargetDTO();
        fulfillmentTarget.setSchemaCode(source.getTarget().getSchemaCode());
        fulfillmentTarget.setSchemaVersion(source.getTarget().getSchemaVersion());
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

    private static void assertCounts(JdbcTemplate jdbc, long receipts, long orders, long requests,
                                     long rounds, long bOrders, long operations) {
        assertThat(count(jdbc, "sc_fulfillment_acceptance_receipt")).isEqualTo(receipts);
        assertThat(count(jdbc, "sc_supply_chain_order")).isEqualTo(orders);
        assertThat(count(jdbc, "sc_fulfillment_request")).isEqualTo(requests);
        assertThat(count(jdbc, "sc_supplier_routing_round")).isEqualTo(rounds);
        assertThat(count(jdbc, "sc_b_order")).isEqualTo(bOrders);
        assertThat(count(jdbc, "sc_fulfillment_operation")).isEqualTo(operations);
    }

    private static long count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private static String rejection(JdbcTemplate jdbc, String buyerOrderNo) {
        return jdbc.queryForObject("SELECT rejection_code FROM sc_fulfillment_acceptance_receipt "
                + "WHERE buyer_order_no=?", String.class, buyerOrderNo);
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
    @Import({SupplyChainDataSourceConfiguration.class, UlidGenerator.class, BusinessNoGenerator.class,
            OutboxEventRepository.class, BuyerCommandService.class, BuyerResolver.class,
            ProductCatalogCommandService.class, ProductCenterTestConfiguration.class, BuyerListingPublicationService.class,
            BuyerAccountOpeningService.class, FundReservationService.class, AccountLedgerWriter.class,
            RoutingPolicyPublicationService.class, SupplierRoutingApplicationService.class,
            ClosedJsonSchemaValidator.class, FulfillmentTargetSchemaRegistry.class,
            AesGcmEnvelopeCrypto.class, FulfillmentAcceptanceLifecycle.class,
            OrderSummaryPolicyRegistry.class, CardOrderSummaryPolicy.class,
            BuyerFulfillmentCommandFacade.class})
    static class TestConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        MutableFailureInjector failureInjector() {
            return new MutableFailureInjector();
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

    private record Fixture(Long buyerId, String listingNo, SupplierDO supplier) {
    }
}
