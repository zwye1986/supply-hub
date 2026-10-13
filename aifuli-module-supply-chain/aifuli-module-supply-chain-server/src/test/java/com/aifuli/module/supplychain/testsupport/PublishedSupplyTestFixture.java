package com.aifuli.module.supplychain.testsupport;

import com.aifuli.module.supplychain.supplieradapter.metadata.*;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierOfferPublicationService;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.*;
import org.springframework.context.ApplicationContext;
import java.time.LocalDateTime;
import java.util.List;

/** 真实Mapper建立完整有效供给；资格/定价测试禁止模拟任意最低价。 */
public final class PublishedSupplyTestFixture {
    private PublishedSupplyTestFixture() { }
    public static SupplierOfferDO create(ApplicationContext context, Long skuId, long cost, LocalDateTime now) {
        String suffix = skuId + "_" + java.util.UUID.randomUUID().toString().substring(0,8);
        var supplier = SupplierDO.builder().supplierCode("SUP_"+suffix).supplierName("测试供应商").shortName("测试")
                .supplierType("STANDARD_SUPPLIER").status((short)10).version(0L).createdAt(now).updatedAt(now).build();
        context.getBean(SupplierMapper.class).insert(supplier);
        var credential = CredentialSlotDO.builder().slotNo("CRD_"+suffix).providerType("ENV").secretRef("env:TEST")
                .maskedHint("test").activeSecretVersion("v1").status((short)10).version(0L).createdAt(now).updatedAt(now).build();
        context.getBean(CredentialSlotMapper.class).insert(credential);
        var connector = SupplierConnectorDO.builder().connectorNo("CON_"+suffix).supplierId(supplier.getId())
                .connectorCode("PRIMARY").adapterType("QNM_GAME_ORDER_V1").status((short)10).version(0L).createdAt(now).updatedAt(now).build();
        context.getBean(SupplierConnectorMapper.class).insert(connector);
        var connectorVersion = SupplierConnectorVersionDO.builder().connectorId(connector.getId()).versionNo(1)
                .baseUrl("https://fixture.invalid").merchantAccount("test").credentialSlotId(credential.getId()).protocolVersion("V1")
                .callbackPath("/callback").connectTimeoutMs(1000).readTimeoutMs(1000).rateLimit(10).concurrencyLimit(2)
                .adapterConfigSchemaVersion(1).adapterConfigJson("{}").validationStatus((short)10).configDigest(new byte[32])
                .status((short)10).version(0L).createdAt(now).updatedAt(now).build();
        context.getBean(SupplierConnectorVersionMapper.class).insert(connectorVersion);
        connector.setCurrentVersionId(connectorVersion.getId()); context.getBean(SupplierConnectorMapper.class).updateById(connector);
        var sku = context.getBean(PlatformSkuMapper.class).selectById(skuId);
        String primary = SupplierOfferPublicationService.derivePrimaryCapability(sku.getFulfillmentType()).name();
        for(String code:List.of(primary,"QUERY_ORDER","RECEIVE_CALLBACK")) {
            context.getBean(SupplierConnectorCapabilityMapper.class).insert(SupplierConnectorCapabilityDO.builder()
                    .connectorVersionId(connectorVersion.getId()).capabilityCode(code).enabled((short)1).metadataVersion(1).createdAt(now).build());
        }
        var inventory = InventorySourceDO.builder().sourceNo("INV_"+suffix).supplierId(supplier.getId()).platformSkuId(skuId)
                .sourceType("REMOTE_ON_DEMAND").externalSourceCode("EXT_"+suffix).ownerType("SUPPLIER")
                .status((short)10).version(0L).createdAt(now).updatedAt(now).build();
        context.getBean(InventorySourceMapper.class).insert(inventory);
        var offer = SupplierOfferDO.builder().offerNo("OFR_"+suffix).offerCode("OFFER_"+suffix).supplierId(supplier.getId())
                .platformSkuId(skuId).externalSkuCode("EXT_"+suffix).priceMode("FIXED").draftUnitPrice(cost)
                .draftMinQuantity(1L).draftMaxQuantity(100L).status((short)10).version(0L).createdAt(now).updatedAt(now).build();
        context.getBean(SupplierOfferMapper.class).insert(offer);
        var version = SupplierOfferVersionDO.builder().offerId(offer.getId()).versionNo(1).externalSkuCode(offer.getExternalSkuCode())
                .supplierSettlementUnitPrice(cost).currency("CNY").connectorVersionId(connectorVersion.getId()).inventorySourceId(inventory.getId())
                .minQuantity(1L).maxQuantity(100L).quantityStep(1L).primaryCapabilityCode(primary).completionMode("SYNCHRONOUS")
                .createIdempotencyMode("DEDUP_ONLY").unknownResultPolicy("MANUAL").partialDeliveryMode("FORBIDDEN").resultMultiplicity("SINGLE")
                .fulfillmentConfigSchemaVersion(1).fulfillmentConfigJson("{}").validFrom(now.minusDays(1))
                .publishReason("集成测试有效供给种子").publishedBy(1L).publishedAt(now).createdAt(now).build();
        context.getBean(SupplierOfferVersionMapper.class).insert(version);
        offer.setCurrentVersionId(version.getId()); context.getBean(SupplierOfferMapper.class).updateById(offer);
        return offer;
    }
}
