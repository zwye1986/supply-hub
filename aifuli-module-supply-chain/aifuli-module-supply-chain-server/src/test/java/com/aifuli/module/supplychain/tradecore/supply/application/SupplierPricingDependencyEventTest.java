package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuPricingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.PricingDependencyChangedEvent;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.supply.domain.ConnectorVersionStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.supplieradapter.metadata.StatusMappingDefinitionMapper;
import com.aifuli.module.supplychain.supplieradapter.metadata.SupplierErrorCodeMapMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * 生命周期批量同步事件须先收齐目标并升序取得全部平台定价根，避免首个 SKU 的下游锁先于后续根锁。
 * 设计见 docs/superpowers/specs/2026-10-19-suggested-sale-price-supply-base-design.md#61-主动变化。
 */
@ExtendWith(MockitoExtension.class)
class SupplierPricingDependencyEventTest {
    @Mock SupplyChainDatabaseClock databaseClock;
    @Mock SupplierMapper supplierMapper;
    @Mock SupplierOfferMapper offerMapper;
    @Mock PlatformSkuPricingMapper pricingMapper;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock SupplierConnectorMapper connectorMapper;
    @Mock SupplierConnectorVersionMapper versionMapper;
    @Mock StatusMappingDefinitionMapper statusMappingMapper;
    @Mock SupplierErrorCodeMapMapper errorCodeMapMapper;
    @Mock SupplierConfigPublishRecordMapper publishRecordMapper;
    @Mock BusinessNoGenerator businessNoGenerator;
    @Mock OutboxEventRepository outboxRepository;
    @InjectMocks SupplierProfileCommandService supplierService;
    @InjectMocks ConnectorPublicationService connectorService;

    @ParameterizedTest
    @EnumSource(SupplierStatus.class)
    void supplierStatusChangeLocksAllDistinctRootsBeforeFirstEvent(SupplierStatus target) {
        SupplierStatus current = target == SupplierStatus.ACTIVE ? SupplierStatus.SUSPENDED : SupplierStatus.ACTIVE;
        SupplierDO supplier = SupplierDO.builder().id(1L).supplierCode("SUP").status(current.getCode()).build();
        when(supplierMapper.selectByCodeForUpdate("SUP")).thenReturn(supplier);
        when(supplierMapper.updateById(supplier)).thenReturn(1);
        when(offerMapper.selectPublishedBySupplier(1L)).thenReturn(offers());

        supplierService.changeStatus("SUP", target, 1L);

        verifyBatchLockThenEvents();
    }

    @org.junit.jupiter.api.BeforeEach
    void initializeMappingMetadata() {
        org.springframework.test.util.ReflectionTestUtils.setField(supplierService, "pricingMapper", pricingMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(connectorService, "pricingMapper", pricingMapper);
        lenient().when(pricingMapper.selectBySkuIdForUpdate(any())).thenAnswer(call ->
                com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuPricingDO.builder()
                        .platformSkuId(call.getArgument(0)).build());
        var config = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        for (Class<?> entity : List.of(
                com.aifuli.module.supplychain.supplieradapter.metadata.StatusMappingDefinitionDO.class,
                com.aifuli.module.supplychain.supplieradapter.metadata.SupplierErrorCodeMapDO.class,
                SupplierConnectorDO.class)) {
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                    new org.apache.ibatis.builder.MapperBuilderAssistant(config, ""), entity);
        }
    }

    @Test
    void connectorRetirementLocksAllDistinctRootsBeforeFirstEvent() {
        SupplierConnectorDO connector = SupplierConnectorDO.builder().id(2L).supplierId(1L)
                .connectorNo("CON").currentVersionId(3L).version(0L).build();
        SupplierConnectorVersionDO version = SupplierConnectorVersionDO.builder().id(3L).connectorId(2L)
                .versionNo(1).status(ConnectorVersionStatus.PUBLISHED.getCode()).build();
        when(connectorMapper.selectByNo("CON")).thenReturn(connector);
        when(supplierMapper.selectByIdForUpdate(1L)).thenReturn(SupplierDO.builder().id(1L).build());
        when(connectorMapper.selectByNoForUpdate("CON")).thenReturn(connector);
        when(versionMapper.selectByIdForUpdate(3L)).thenReturn(version);
        when(versionMapper.updateById(version)).thenReturn(1);
        when(connectorMapper.update(isNull(), any())).thenReturn(1);
        when(offerMapper.selectPublishedBySupplier(1L)).thenReturn(offers());

        connectorService.retire(new ConnectorPublicationService.RetireConnectorVersionCommand(
                "CON", 3L, 1L, "停用", "trace", null, null));

        verifyBatchLockThenEvents();
    }

    private List<SupplierOfferDO> offers() {
        return List.of(SupplierOfferDO.builder().platformSkuId(9L).build(),
                SupplierOfferDO.builder().platformSkuId(4L).build(),
                SupplierOfferDO.builder().platformSkuId(9L).build(),
                SupplierOfferDO.builder().build());
    }

    private void verifyBatchLockThenEvents() {
        var order = inOrder(pricingMapper, eventPublisher);
        order.verify(pricingMapper).selectBySkuIdForUpdate(4L);
        order.verify(pricingMapper).selectBySkuIdForUpdate(9L);
        order.verify(eventPublisher).publishEvent(new PricingDependencyChangedEvent(4L));
        order.verify(eventPublisher).publishEvent(new PricingDependencyChangedEvent(9L));
        order.verifyNoMoreInteractions();
    }
}
