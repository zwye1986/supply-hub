package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadataRegistry;
import com.aifuli.module.supplychain.supplieradapter.metadata.QnmGameOrderV1Metadata;
import com.aifuli.module.supplychain.supplieradapter.metadata.StatusMappingDefinitionDO;
import com.aifuli.module.supplychain.supplieradapter.metadata.StatusMappingDefinitionMapper;
import com.aifuli.module.supplychain.supplieradapter.metadata.SupplierErrorCodeMapDO;
import com.aifuli.module.supplychain.supplieradapter.metadata.SupplierErrorCodeMapMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.ConnectorVersionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 映射与验证共用连接器版本围栏：先当前读锁版本，写完再推进乐观锁。
 * 设计见 docs/superpowers/specs/2026-10-12-huasheng-fulfillment-hardening-design.md#31-后端元数据与查询。
 */
@ExtendWith(MockitoExtension.class)
class SupplierConfigurationMappingVersionFenceTest {
    private static final long VERSION_ID = 99L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 12, 14, 0);
    @Mock private SupplyChainDatabaseClock databaseClock;
    @Mock private SupplierConnectorMapper connectorMapper;
    @Mock private SupplierConnectorVersionMapper versionMapper;
    @Mock private StatusMappingDefinitionMapper statusMappingMapper;
    @Mock private SupplierErrorCodeMapMapper errorCodeMapMapper;
    @Mock private AdapterMetadataRegistry metadataRegistry;
    @InjectMocks private SupplierConfigurationCommandService command;

    enum Write { INITIALIZE, STATUS, ERROR }

    @ParameterizedTest
    @EnumSource(Write.class)
    void everyWriteLocksVersionThenTouchesItAfterInsert(Write write) {
        SupplierConnectorVersionDO locked = draft();
        stub(locked, 1);
        run(write);
        var order = inOrder(versionMapper, statusMappingMapper, errorCodeMapMapper);
        order.verify(versionMapper).selectByIdForUpdate(VERSION_ID);
        if (write == Write.ERROR) {
            order.verify(errorCodeMapMapper).insert(any(SupplierErrorCodeMapDO.class));
        } else {
            order.verify(statusMappingMapper, times(write == Write.INITIALIZE ? 7 : 1))
                    .insert(any(StatusMappingDefinitionDO.class));
        }
        order.verify(versionMapper).updateById(locked);
        assertThat(locked.getUpdatedAt()).isEqualTo(NOW);
        assertThat(locked.getUpdatedBy()).isEqualTo(7L);
        // 真实版本号增量由 MyBatis @Version 插件负责；这里确认携带旧版本进入乐观锁更新。
        assertThat(locked.getVersion()).isEqualTo(4L);
        verify(versionMapper, never()).selectById(VERSION_ID);
    }

    @ParameterizedTest
    @EnumSource(Write.class)
    void staleDraftReadCannotBypassValidatedCurrentRead(Write write) {
        lenient().when(versionMapper.selectById(VERSION_ID)).thenReturn(draft());
        SupplierConnectorVersionDO locked = draft();
        locked.setStatus(ConnectorVersionStatus.VALIDATED.getCode());
        lenient().when(versionMapper.selectByIdForUpdate(VERSION_ID)).thenReturn(locked);
        lenient().when(connectorMapper.selectById(10L)).thenReturn(SupplierConnectorDO.builder()
                .id(10L).adapterType(QnmGameOrderV1Metadata.TYPE).build());
        lenient().when(metadataRegistry.require(QnmGameOrderV1Metadata.TYPE)).thenReturn(new QnmGameOrderV1Metadata());
        assertThatThrownBy(() -> run(write)).isInstanceOf(IllegalStateException.class).hasMessageContaining("draft");
        verify(statusMappingMapper, never()).insert(any(StatusMappingDefinitionDO.class));
        verify(errorCodeMapMapper, never()).insert(any(SupplierErrorCodeMapDO.class));
        verify(versionMapper, never()).updateById(any(SupplierConnectorVersionDO.class));
    }

    @ParameterizedTest
    @EnumSource(Write.class)
    void versionTouchFailureRejectsTheWholeMappingTransaction(Write write) {
        stub(draft(), 0);
        assertThatThrownBy(() -> run(write)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("optimistic lock");
    }

    private void stub(SupplierConnectorVersionDO version, int updatedRows) {
        // 红测阶段旧实现只读普通快照；两路桩允许测试先编译并失败于围栏缺失。
        lenient().when(versionMapper.selectById(VERSION_ID)).thenReturn(version);
        lenient().when(versionMapper.selectByIdForUpdate(VERSION_ID)).thenReturn(version);
        lenient().when(versionMapper.updateById(any(SupplierConnectorVersionDO.class))).thenReturn(updatedRows);
        lenient().when(connectorMapper.selectById(10L)).thenReturn(SupplierConnectorDO.builder()
                .id(10L).adapterType(QnmGameOrderV1Metadata.TYPE).build());
        lenient().when(metadataRegistry.require(QnmGameOrderV1Metadata.TYPE)).thenReturn(new QnmGameOrderV1Metadata());
        lenient().when(databaseClock.now()).thenReturn(NOW);
    }

    private SupplierConnectorVersionDO draft() {
        return SupplierConnectorVersionDO.builder().id(VERSION_ID).connectorId(10L).versionNo(2)
                .status(ConnectorVersionStatus.DRAFT.getCode()).version(4L).build();
    }

    private void run(Write write) {
        switch (write) {
            case INITIALIZE -> command.initializeMappings(VERSION_ID, 7L);
            case STATUS -> command.addStatusMapping(new SupplierConfigurationCommandService.AddStatusMappingCommand(
                    VERSION_ID, "orderStatus=9", "", "QUERY", "SUCCEEDED", true, 7L));
            case ERROR -> command.addErrorCodeMapping(new SupplierConfigurationCommandService.AddErrorCodeMappingCommand(
                    VERSION_ID, "E_BUSY", "", "RETRYABLE", "RETRY_SAFE", true, 7L));
        }
    }
}
