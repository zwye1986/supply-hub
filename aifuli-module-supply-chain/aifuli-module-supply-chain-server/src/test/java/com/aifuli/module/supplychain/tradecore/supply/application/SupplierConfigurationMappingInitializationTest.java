package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadata;
import com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadataRegistry;
import com.aifuli.module.supplychain.supplieradapter.metadata.MappingTemplates;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G2 映射模板初始化/追加的纯逻辑单测：验证 {@code initializeMappings} 适配器无关地从
 * {@link AdapterMetadata#mappingTemplates()} 播种状态与错误码映射，以及 {@code addStatusMapping} 追加单条。
 *
 * <p>设计见 docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#65-映射g2。</p>
 */
@ExtendWith(MockitoExtension.class)
class SupplierConfigurationMappingInitializationTest {

    private static final String STUB_ADAPTER = "STUB_ADAPTER_V1";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 3, 9, 0, 0);
    private static final long VERSION_ID = 99L;
    private static final long CONNECTOR_ID = 10L;

    @Mock
    private SupplyChainDatabaseClock databaseClock;
    @Mock
    private SupplierConnectorMapper connectorMapper;
    @Mock
    private SupplierConnectorVersionMapper versionMapper;
    @Mock
    private StatusMappingDefinitionMapper statusMappingMapper;
    @Mock
    private SupplierErrorCodeMapMapper errorCodeMapMapper;
    @Mock
    private AdapterMetadataRegistry metadataRegistry;
    @Mock
    private AdapterMetadata metadata;

    @InjectMocks
    private SupplierConfigurationCommandService service;

    @Test
    void initializeMappingsSeedsStatusAndErrorTemplatesForAnyAdapterType() {
        stubDraftVersion();
        when(metadataRegistry.require(STUB_ADAPTER)).thenReturn(metadata);
        when(metadata.mappingTemplates()).thenReturn(new MappingTemplates(
                List.of(new MappingTemplates.StatusMappingTemplate("s=1", "", "QUERY", "SUCCEEDED", true),
                        new MappingTemplates.StatusMappingTemplate("s=0", "", "QUERY", "ACCEPTED", false)),
                List.of(new MappingTemplates.ErrorCodeMappingTemplate(
                        "E_TIMEOUT", "", "RESULT_UNKNOWN", "QUERY_FIRST", false))));
        when(statusMappingMapper.selectByConnectorVersionId(VERSION_ID)).thenReturn(List.of());
        when(databaseClock.now()).thenReturn(NOW);

        int count = service.initializeMappings(VERSION_ID, 1L);

        assertThat(count).isEqualTo(3);
        ArgumentCaptor<StatusMappingDefinitionDO> statusCaptor =
                ArgumentCaptor.forClass(StatusMappingDefinitionDO.class);
        verify(statusMappingMapper, times(2)).insert(statusCaptor.capture());
        assertThat(statusCaptor.getAllValues())
                .extracting(StatusMappingDefinitionDO::getExternalStatus)
                .containsExactly("s=1", "s=0");
        ArgumentCaptor<SupplierErrorCodeMapDO> errorCaptor =
                ArgumentCaptor.forClass(SupplierErrorCodeMapDO.class);
        verify(errorCodeMapMapper, times(1)).insert(errorCaptor.capture());
        SupplierErrorCodeMapDO error = errorCaptor.getValue();
        assertThat(error.getExternalErrorCode()).isEqualTo("E_TIMEOUT");
        assertThat(error.getErrorClass()).isEqualTo("RESULT_UNKNOWN");
        assertThat(error.getActionType()).isEqualTo("QUERY_FIRST");
        assertThat(error.getRetryableFlag()).isEqualTo((short) 0);
        assertThat(error.getStatus()).isEqualTo((short) 0);
    }

    @Test
    void initializeMappingsRejectsWhenAlreadyInitialized() {
        stubDraftVersion();
        when(statusMappingMapper.selectByConnectorVersionId(VERSION_ID))
                .thenReturn(List.of(StatusMappingDefinitionDO.builder().build()));

        assertThatThrownBy(() -> service.initializeMappings(VERSION_ID, 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been initialized");
        verify(statusMappingMapper, never()).insert(any(StatusMappingDefinitionDO.class));
        verify(errorCodeMapMapper, never()).insert(any(SupplierErrorCodeMapDO.class));
    }

    @Test
    void addStatusMappingAppendsSingleDraftRow() {
        when(versionMapper.selectById(VERSION_ID)).thenReturn(SupplierConnectorVersionDO.builder()
                .id(VERSION_ID).connectorId(CONNECTOR_ID).versionNo(2)
                .status(ConnectorVersionStatus.DRAFT.getCode()).build());
        when(databaseClock.now()).thenReturn(NOW);

        service.addStatusMapping(new SupplierConfigurationCommandService.AddStatusMappingCommand(
                VERSION_ID, "orderStatus=9", "", "QUERY", "SUCCEEDED", true, 1L));

        ArgumentCaptor<StatusMappingDefinitionDO> captor =
                ArgumentCaptor.forClass(StatusMappingDefinitionDO.class);
        verify(statusMappingMapper).insert(captor.capture());
        StatusMappingDefinitionDO row = captor.getValue();
        assertThat(row.getExternalStatus()).isEqualTo("orderStatus=9");
        assertThat(row.getFactType()).isEqualTo("SUCCEEDED");
        assertThat(row.getTerminalFlag()).isEqualTo((short) 1);
        assertThat(row.getMappingVersion()).isEqualTo(2);
        assertThat(row.getStatus()).isEqualTo((short) 0);
    }

    @Test
    void addStatusMappingRejectsFactOutsideClosedSet() {
        when(versionMapper.selectById(VERSION_ID)).thenReturn(SupplierConnectorVersionDO.builder()
                .id(VERSION_ID).connectorId(CONNECTOR_ID).versionNo(1)
                .status(ConnectorVersionStatus.DRAFT.getCode()).build());

        assertThatThrownBy(() -> service.addStatusMapping(
                new SupplierConfigurationCommandService.AddStatusMappingCommand(
                        VERSION_ID, "x", "", "QUERY", "MAYBE", false, 1L)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(statusMappingMapper, never()).insert(any(StatusMappingDefinitionDO.class));
    }

    private void stubDraftVersion() {
        when(versionMapper.selectById(VERSION_ID)).thenReturn(SupplierConnectorVersionDO.builder()
                .id(VERSION_ID).connectorId(CONNECTOR_ID).versionNo(1)
                .status(ConnectorVersionStatus.DRAFT.getCode()).build());
        when(connectorMapper.selectById(CONNECTOR_ID)).thenReturn(SupplierConnectorDO.builder()
                .id(CONNECTOR_ID).adapterType(STUB_ADAPTER).build());
    }
}
