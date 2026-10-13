package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.infrastructure.security.AdapterTypeAllowList;
import com.aifuli.module.supplychain.supplieradapter.metadata.*;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductSellabilityService;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.*;
import java.util.List;
import java.util.ArrayList;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.mock;

/** 测试使用真实资格解析器；只模拟数据访问边界，不伪造正式最低供货价。 */
public final class SupplierEligibilityTestFixture {
    private SupplierEligibilityTestFixture() { }
    public static SupplierOfferEligibilityService create(SupplierMapper suppliers, SupplierOfferMapper offers,
            SupplierOfferVersionMapper versions, PlatformSkuMapper skus, PlatformSpuMapper spus) {
        return new SupplierOfferEligibilityService(suppliers, mock(SupplierConnectorMapper.class),
                mock(SupplierConnectorVersionMapper.class), mock(SupplierConnectorCapabilityMapper.class),
                mock(InventorySourceMapper.class), offers, versions, skus, spus,
                new ProductSellabilityService(skus, spus), mock(AdapterTypeAllowList.class),
                new AdapterMetadataRegistry(List.of()));
    }
    /** 旧列表种子显式补齐当前依赖；保留原供给价格、有效区间与供应商状态。 */
    public static void seedDependencies(Object catalog, List<PlatformSkuDO> skuRows, List<PlatformSpuDO> spuRows) {
        SupplierOfferEligibilityService eligibility = (SupplierOfferEligibilityService) ReflectionTestUtils.getField(catalog, "eligibilityService");
        SupplierOfferMapper offers = (SupplierOfferMapper) ReflectionTestUtils.getField(eligibility, "offerMapper");
        SupplierOfferVersionMapper versions = (SupplierOfferVersionMapper) ReflectionTestUtils.getField(eligibility, "offerVersionMapper");
        SupplierMapper suppliers = (SupplierMapper) ReflectionTestUtils.getField(eligibility, "supplierMapper");
        List<SupplierOfferDO> offerRows = offers.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>());
        List<SupplierOfferVersionDO> versionRows = versions.selectBatchIds(offerRows.stream().map(SupplierOfferDO::getCurrentVersionId).filter(java.util.Objects::nonNull).toList());
        SupplierConnectorMapper connectors = (SupplierConnectorMapper) ReflectionTestUtils.getField(eligibility, "connectorMapper");
        SupplierConnectorVersionMapper connectorVersions = (SupplierConnectorVersionMapper) ReflectionTestUtils.getField(eligibility, "connectorVersionMapper");
        InventorySourceMapper inventory = (InventorySourceMapper) ReflectionTestUtils.getField(eligibility, "inventorySourceMapper");
        SupplierConnectorCapabilityMapper capabilities = (SupplierConnectorCapabilityMapper) ReflectionTestUtils.getField(eligibility, "capabilityMapper");
        AdapterTypeAllowList allow = (AdapterTypeAllowList) ReflectionTestUtils.getField(eligibility, "adapterTypeAllowList");
        ArrayList<SupplierConnectorDO> connectorRows = new ArrayList<>();
        ArrayList<SupplierConnectorVersionDO> connectorVersionRows = new ArrayList<>();
        ArrayList<InventorySourceDO> inventoryRows = new ArrayList<>();
        ArrayList<SupplierConnectorCapabilityDO> capabilityRows = new ArrayList<>();
        for (SupplierOfferDO offer : offerRows) {
            SupplierOfferVersionDO version = versionRows.stream().filter(v -> v.getId().equals(offer.getCurrentVersionId())).findFirst().orElse(null);
            if (version == null) continue;
            long connectorId = offer.getId() + 100_000L;
            long connectorVersionId = offer.getId() + 200_000L;
            long inventoryId = offer.getId() + 300_000L;
            version.setOfferId(offer.getId()); version.setCurrency("CNY");
            version.setConnectorVersionId(connectorVersionId); version.setInventorySourceId(inventoryId);
            version.setMinQuantity(1L); version.setMaxQuantity(100L); version.setQuantityStep(1L);
            version.setPrimaryCapabilityCode("ISSUE_ASSET"); version.setCompletionMode("SYNCHRONOUS"); version.setUnknownResultPolicy("MANUAL");
            connectorRows.add(SupplierConnectorDO.builder().id(connectorId).supplierId(offer.getSupplierId())
                    .status((short)10).adapterType("TEST").currentVersionId(connectorVersionId).build());
            connectorVersionRows.add(SupplierConnectorVersionDO.builder().id(connectorVersionId).connectorId(connectorId).status((short)10).build());
            inventoryRows.add(InventorySourceDO.builder().id(inventoryId).supplierId(offer.getSupplierId()).platformSkuId(offer.getPlatformSkuId())
                    .status((short)10).sourceType("REMOTE_ON_DEMAND").build());
            capabilityRows.add(SupplierConnectorCapabilityDO.builder().connectorVersionId(connectorVersionId).capabilityCode("ISSUE_ASSET").enabled((short)1).build());
        }
        skuRows.forEach(sku -> { if (sku.getFulfillmentType() == null) sku.setFulfillmentType("VOUCHER_CODE"); });
        when(((PlatformSkuMapper)ReflectionTestUtils.getField(eligibility,"skuMapper")).selectBatchIds(any())).thenReturn(skuRows);
        when(((PlatformSpuMapper)ReflectionTestUtils.getField(eligibility,"spuMapper")).selectBatchIds(any())).thenReturn(spuRows);
        when(connectors.selectBatchIds(any())).thenReturn(connectorRows);
        when(connectorVersions.selectBatchIds(any())).thenReturn(connectorVersionRows);
        when(inventory.selectBatchIds(any())).thenReturn(inventoryRows);
        when(capabilities.selectList(any())).thenReturn(capabilityRows);
        when(allow.isAllowed("TEST")).thenReturn(true);
        clearInvocations(offers, versions, suppliers);
    }

}
