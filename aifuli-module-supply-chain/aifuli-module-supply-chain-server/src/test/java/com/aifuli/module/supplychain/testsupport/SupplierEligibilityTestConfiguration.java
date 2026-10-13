package com.aifuli.module.supplychain.testsupport;

import com.aifuli.module.supplychain.infrastructure.security.AdapterTypeAllowList;
import com.aifuli.module.supplychain.supplieradapter.metadata.*;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductSellabilityService;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierOfferEligibilityService;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import java.util.List;

/** 显式Bean集成测试共用真实资格解析器；白名单仍取测试配置，空配置保持拒绝。 */
@Configuration(proxyBeanMethods = false)
public class SupplierEligibilityTestConfiguration {
    @Bean @ConditionalOnMissingBean
    SupplierOfferEligibilityService supplierOfferEligibilityService(SupplierMapper suppliers,
            SupplierConnectorMapper connectors, SupplierConnectorVersionMapper connectorVersions,
            SupplierConnectorCapabilityMapper capabilities, InventorySourceMapper inventory,
            SupplierOfferMapper offers, SupplierOfferVersionMapper offerVersions,
            PlatformSkuMapper skus, PlatformSpuMapper spus, ProductSellabilityService sellability,
            ObjectProvider<AdapterTypeAllowList> allowLists, ObjectProvider<AdapterMetadataRegistry> registries, Environment environment) {
        return new SupplierOfferEligibilityService(suppliers, connectors, connectorVersions, capabilities,
                inventory, offers, offerVersions, skus, spus, sellability,
                allowLists.getIfAvailable(() -> new AdapterTypeAllowList(environment)),
                registries.getIfAvailable(() -> new AdapterMetadataRegistry(List.of(new QnmGameOrderV1Metadata()))));
    }
}
