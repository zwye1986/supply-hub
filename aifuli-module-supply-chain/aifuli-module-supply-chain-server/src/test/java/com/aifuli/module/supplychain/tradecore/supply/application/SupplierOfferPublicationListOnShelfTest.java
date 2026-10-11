package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.infrastructure.security.AdapterTypeAllowList;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadataRegistry;
import com.aifuli.module.supplychain.supplieradapter.metadata.ClosedJsonSchemaValidator;
import com.aifuli.module.supplychain.supplieradapter.metadata.QnmGameOrderV1Metadata;
import com.aifuli.module.supplychain.supplieradapter.metadata.SupplierConnectorCapabilityMapper;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductSellabilityService;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierOfferPublicationService.ListOnShelfCommand;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.InventorySourceMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierExternalSkuBindingMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.ConnectorVersionStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.OFFER_CAPABILITY_MISSING;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_NO_PUBLISHED_CONNECTOR;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B1 上架即发布的能力缺口与连接器缺失守卫分支（§6.6 步骤 2/3，§9 *Test）。
 *
 * <p>设计见 {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#66-上架即发布服务b1核心}：
 * 无唯一已发布连接器（或 adapterType 不在白名单）→ {@code SUPPLIER_NO_PUBLISHED_CONNECTOR}；连接器未启用 SKU
 * 推导出的主能力 → {@code OFFER_CAPABILITY_MISSING}。happy path 由 {@code *IntegrationTest} 覆盖（§9 ③）。</p>
 */
class SupplierOfferPublicationListOnShelfTest {

    private static final String OFFER_NO = "OFR_1";
    private static final String SUPPLIER_CODE = "SPL_QNM";
    private static final long SUPPLIER_ID = 7L;
    private static final long SKU_ID = 3L;
    private static final long CONNECTOR_ID = 11L;
    private static final long CONNECTOR_VERSION_ID = 21L;

    private final SupplierMapper supplierMapper = mock(SupplierMapper.class);
    private final SupplierConnectorMapper connectorMapper = mock(SupplierConnectorMapper.class);
    private final SupplierConnectorVersionMapper connectorVersionMapper = mock(SupplierConnectorVersionMapper.class);
    private final SupplierConnectorCapabilityMapper capabilityMapper = mock(SupplierConnectorCapabilityMapper.class);
    private final InventorySourceMapper inventorySourceMapper = mock(InventorySourceMapper.class);
    private final SupplierOfferMapper offerMapper = mock(SupplierOfferMapper.class);
    private final SupplierOfferVersionMapper offerVersionMapper = mock(SupplierOfferVersionMapper.class);
    private final SupplierExternalSkuBindingMapper externalSkuBindingMapper =
            mock(SupplierExternalSkuBindingMapper.class);
    private final ClosedJsonSchemaValidator schemaValidator = mock(ClosedJsonSchemaValidator.class);
    private final BusinessNoGenerator businessNoGenerator = mock(BusinessNoGenerator.class);
    private final OutboxEventRepository outboxRepository = mock(OutboxEventRepository.class);
    private final ProductSellabilityService productSellabilityService = mock(ProductSellabilityService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final SupplyChainDatabaseClock databaseClock = mock(SupplyChainDatabaseClock.class);
    private final AdapterTypeAllowList adapterTypeAllowList = mock(AdapterTypeAllowList.class);

    private SupplierOfferPublicationService service;

    @BeforeEach
    void setUp() {
        service = new SupplierOfferPublicationService(
                databaseClock, supplierMapper, connectorMapper, connectorVersionMapper, capabilityMapper,
                inventorySourceMapper, offerMapper, offerVersionMapper, externalSkuBindingMapper,
                new AdapterMetadataRegistry(List.of(new QnmGameOrderV1Metadata())), schemaValidator,
                businessNoGenerator, outboxRepository, new ObjectMapper(), productSellabilityService,
                eventPublisher, adapterTypeAllowList, mock(com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialSetMapper.class));
    }

    private void stubOfferSupplierAndSku(String fulfillmentType) {
        SupplierOfferDO offer = SupplierOfferDO.builder()
                .id(1L).offerNo(OFFER_NO).supplierId(SUPPLIER_ID).platformSkuId(SKU_ID)
                .externalSkuCode("QNM-EXT-1").priceMode("FIXED").draftUnitPrice(1999L)
                .draftMinQuantity(1L).draftMaxQuantity(100L)
                .status(OfferStatus.PUBLISHED.getCode()).version(0L).build();
        when(offerMapper.selectByNoForUpdate(OFFER_NO)).thenReturn(offer);
        when(offerMapper.selectByNo(OFFER_NO)).thenReturn(offer);
        SupplierDO supplier = SupplierDO.builder()
                .id(SUPPLIER_ID).supplierCode(SUPPLIER_CODE).supplierType("STANDARD_SUPPLIER")
                .status(SupplierStatus.ACTIVE.getCode()).build();
        when(supplierMapper.selectByCodeForUpdate(SUPPLIER_CODE)).thenReturn(supplier);
        PlatformSkuDO sku = PlatformSkuDO.builder().id(SKU_ID).fulfillmentType(fulfillmentType).build();
        when(productSellabilityService.requireSellableById(SKU_ID))
                .thenReturn(new ProductSellabilityService.SellableProduct(sku, new PlatformSpuDO()));
    }

    /** 技术端新增同样遵循供应商互斥与默认下架，不得旁路生命周期。 */
    @Test void technicalCreateOfferIsSuspendedAndLocksSupplierBeforeInsert() {
        stubTechnicalCreate();
        service.createOffer(new SupplierOfferPublicationService.CreateOfferCommand(SUPPLIER_CODE,"SKU_1","OF_1",1L));
        var row = org.mockito.ArgumentCaptor.forClass(SupplierOfferDO.class);
        var order = org.mockito.Mockito.inOrder(supplierMapper,offerMapper);
        order.verify(supplierMapper).selectByCodeForUpdate(SUPPLIER_CODE);
        order.verify(offerMapper).insert(row.capture());
        org.assertj.core.api.Assertions.assertThat(row.getValue().getStatus()).isEqualTo(OfferStatus.SUSPENDED.getCode());
        org.assertj.core.api.Assertions.assertThat(row.getValue().getCurrentVersionId()).isNull();
    }
    @Test void technicalInventoryCreateLocksSupplierBeforeInsert() {
        stubTechnicalCreate();
        service.createRemoteOnDemandInventory(new SupplierOfferPublicationService.CreateInventorySourceCommand(
                SUPPLIER_CODE,"SKU_1","EXT_1",1L));
        var order = org.mockito.Mockito.inOrder(supplierMapper,inventorySourceMapper);
        order.verify(supplierMapper).selectByCodeForUpdate(SUPPLIER_CODE);
        order.verify(inventorySourceMapper).insert(org.mockito.ArgumentMatchers.any(com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.InventorySourceDO.class));
    }
    @Test void technicalCreatesRejectDefaultOuterIsolationBeforeReadingSupplier() {
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service.createOffer(new SupplierOfferPublicationService.CreateOfferCommand(
                    SUPPLIER_CODE,"SKU_1","OF_1",1L))).isInstanceOf(IllegalStateException.class).hasMessageContaining("READ_COMMITTED");
            assertThatThrownBy(() -> service.createRemoteOnDemandInventory(new SupplierOfferPublicationService.CreateInventorySourceCommand(
                    SUPPLIER_CODE,"SKU_1","EXT_1",1L))).isInstanceOf(IllegalStateException.class).hasMessageContaining("READ_COMMITTED");
            org.mockito.Mockito.verifyNoInteractions(supplierMapper,offerMapper,inventorySourceMapper);
        } finally { org.springframework.transaction.support.TransactionSynchronizationManager.clear(); }
    }
    private void stubTechnicalCreate() {
        SupplierDO supplier = SupplierDO.builder().id(SUPPLIER_ID).supplierCode(SUPPLIER_CODE)
                .supplierType("STANDARD_SUPPLIER").status(SupplierStatus.ACTIVE.getCode()).build();
        when(supplierMapper.selectByCode(SUPPLIER_CODE)).thenReturn(supplier);
        when(supplierMapper.selectByCodeForUpdate(SUPPLIER_CODE)).thenReturn(supplier);
        when(productSellabilityService.requireSellableByCode("SKU_1")).thenReturn(new ProductSellabilityService.SellableProduct(
                PlatformSkuDO.builder().id(SKU_ID).build(),new PlatformSpuDO()));
    }

    private void stubPublishedConnector() {
        SupplierConnectorDO connector = SupplierConnectorDO.builder().id(CONNECTOR_ID).supplierId(SUPPLIER_ID)
                .status((short)10).adapterType(QnmGameOrderV1Metadata.TYPE).currentVersionId(CONNECTOR_VERSION_ID).build();
        SupplierConnectorVersionDO version = SupplierConnectorVersionDO.builder().id(CONNECTOR_VERSION_ID).connectorId(CONNECTOR_ID)
                .status(ConnectorVersionStatus.PUBLISHED.getCode()).build();
        when(connectorMapper.selectPublishedBySupplier(SUPPLIER_ID)).thenReturn(connector);
        when(connectorMapper.selectByIdForUpdate(CONNECTOR_ID)).thenReturn(connector);
        when(connectorVersionMapper.selectById(CONNECTOR_VERSION_ID)).thenReturn(version);
        when(connectorVersionMapper.selectByIdForUpdate(CONNECTOR_VERSION_ID)).thenReturn(version);
        when(adapterTypeAllowList.isAllowed(QnmGameOrderV1Metadata.TYPE)).thenReturn(true);
        when(capabilityMapper.selectByConnectorVersionIdForUpdate(CONNECTOR_VERSION_ID)).thenReturn(List.of());
    }

    private ListOnShelfCommand command() {
        return new ListOnShelfCommand(SUPPLIER_CODE, OFFER_NO, null, 99L, "trace", "corr", "cause");
    }

    @Test
    void rejectsRealtimeDraftBeforeAttemptingPublication() {
        stubOfferSupplierAndSku("VOUCHER_CODE");
        SupplierOfferDO offer = offerMapper.selectByNoForUpdate(OFFER_NO);
        offer.setPriceMode("REALTIME");
        stubPublishedConnector();
        assertThatThrownBy(() -> service.listOnShelf(command()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("FIXED");
    }

    @Test
    void rejectsWhenOfferDoesNotBelongToPathSupplier() {
        SupplierOfferDO offer = SupplierOfferDO.builder()
                .id(1L).offerNo(OFFER_NO).supplierId(999L).platformSkuId(SKU_ID)
                .externalSkuCode("QNM-EXT-1").priceMode("FIXED").draftUnitPrice(1999L)
                .draftMinQuantity(1L).draftMaxQuantity(100L)
                .status(OfferStatus.PUBLISHED.getCode()).version(0L).build();
        when(offerMapper.selectByNoForUpdate(OFFER_NO)).thenReturn(offer);
        when(offerMapper.selectByNo(OFFER_NO)).thenReturn(offer);
        when(supplierMapper.selectByCodeForUpdate(SUPPLIER_CODE)).thenReturn(SupplierDO.builder()
                .id(SUPPLIER_ID).supplierCode(SUPPLIER_CODE).supplierType("STANDARD_SUPPLIER")
                .status(SupplierStatus.ACTIVE.getCode()).build());

        stubPublishedConnector();
        assertThatThrownBy(() -> service.listOnShelf(command()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWhenSupplierHasNoPublishedConnector() {
        stubOfferSupplierAndSku("VOUCHER_CODE");
        when(connectorMapper.selectPublishedBySupplier(SUPPLIER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.listOnShelf(command()))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(SUPPLIER_NO_PUBLISHED_CONNECTOR.getCode());
    }

    @Test
    void rejectsWhenPublishedConnectorAdapterTypeNotWhitelisted() {
        stubOfferSupplierAndSku("VOUCHER_CODE");
        when(connectorMapper.selectPublishedBySupplier(SUPPLIER_ID)).thenReturn(
                SupplierConnectorDO.builder().id(CONNECTOR_ID).supplierId(SUPPLIER_ID)
                        .status((short)10).adapterType(QnmGameOrderV1Metadata.TYPE).currentVersionId(CONNECTOR_VERSION_ID).build());
        when(adapterTypeAllowList.isAllowed(QnmGameOrderV1Metadata.TYPE)).thenReturn(false);

        assertThatThrownBy(() -> service.listOnShelf(command()))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(SUPPLIER_NO_PUBLISHED_CONNECTOR.getCode());
    }

    @Test
    void rejectsWhenConnectorMissingRequiredCapability() {
        stubOfferSupplierAndSku("VOUCHER_CODE");
        when(connectorMapper.selectPublishedBySupplier(SUPPLIER_ID)).thenReturn(
                SupplierConnectorDO.builder().id(CONNECTOR_ID).supplierId(SUPPLIER_ID)
                        .status((short)10).adapterType(QnmGameOrderV1Metadata.TYPE).currentVersionId(CONNECTOR_VERSION_ID).build());
        when(adapterTypeAllowList.isAllowed(QnmGameOrderV1Metadata.TYPE)).thenReturn(true);
        when(connectorVersionMapper.selectByIdForUpdate(CONNECTOR_VERSION_ID)).thenReturn(
                SupplierConnectorVersionDO.builder().id(CONNECTOR_VERSION_ID).connectorId(CONNECTOR_ID)
                        .status(ConnectorVersionStatus.PUBLISHED.getCode()).build());
        SupplierConnectorVersionDO version = connectorVersionMapper.selectByIdForUpdate(CONNECTOR_VERSION_ID);
        when(connectorVersionMapper.selectById(CONNECTOR_VERSION_ID)).thenReturn(version);
        SupplierConnectorDO connector = connectorMapper.selectPublishedBySupplier(SUPPLIER_ID);
        when(connectorMapper.selectByIdForUpdate(CONNECTOR_ID)).thenReturn(connector);
        when(capabilityMapper.selectByConnectorVersionIdForUpdate(CONNECTOR_VERSION_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.listOnShelf(command()))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(OFFER_CAPABILITY_MISSING.getCode());
    }
    @Test
    void locksPublicationDependenciesBeforeOffer() {
        stubOfferSupplierAndSku("VOUCHER_CODE");
        stubPublishedConnector();
        assertThatThrownBy(() -> service.listOnShelf(command())).isInstanceOf(ServiceException.class);
        var ordered = org.mockito.Mockito.inOrder(supplierMapper, connectorMapper, connectorVersionMapper, capabilityMapper, offerMapper);
        ordered.verify(supplierMapper).selectByCodeForUpdate(SUPPLIER_CODE);
        ordered.verify(connectorMapper).selectByIdForUpdate(CONNECTOR_ID);
        ordered.verify(connectorVersionMapper).selectByIdForUpdate(CONNECTOR_VERSION_ID);
        ordered.verify(capabilityMapper).selectByConnectorVersionIdForUpdate(CONNECTOR_VERSION_ID);
        ordered.verify(offerMapper).selectByNoForUpdate(OFFER_NO);
    }

}
