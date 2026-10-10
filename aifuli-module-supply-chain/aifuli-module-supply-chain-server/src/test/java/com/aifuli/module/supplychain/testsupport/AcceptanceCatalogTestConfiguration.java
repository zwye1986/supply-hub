package com.aifuli.module.supplychain.testsupport;

import com.aifuli.module.supplychain.border.fulfillment.application.FrozenAcceptanceIdentityReader;
import com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadataRegistry;
import com.aifuli.module.supplychain.supplieradapter.metadata.QnmGameOrderV1Metadata;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerAcceptanceCatalogResolver;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerSelectionCatalogLifecycle;
import com.aifuli.module.supplychain.tradecore.catalog.application.CurrentSupplyEligibilityReader;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 受理套件共享真实目录依赖；不替换Reader/来源守卫，不启用消费者或外呼。
 * 连接器与executor由原执行fixture提供，旧RPC用例不冒充HTTP准入证据。
 * 见 docs/superpowers/specs/2026-10-09-catalog-business-minimum-design.md#91-2026-10-10-评审修复约束。
 */
@Configuration(proxyBeanMethods = false)
@Import({FrozenAcceptanceIdentityReader.class, BuyerAcceptanceCatalogResolver.class,
        BuyerSelectionCatalogLifecycle.class, CurrentSupplyEligibilityReader.class,
        QnmGameOrderV1Metadata.class, AdapterMetadataRegistry.class})
public class AcceptanceCatalogTestConfiguration {
}
