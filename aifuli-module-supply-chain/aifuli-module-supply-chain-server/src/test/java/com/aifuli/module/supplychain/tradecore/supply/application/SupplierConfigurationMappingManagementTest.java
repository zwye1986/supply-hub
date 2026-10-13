package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadata;
import com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadataRegistry;
import com.aifuli.module.supplychain.supplieradapter.metadata.HuashengOrderV1Metadata;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 映射管理写入口与只读投影的行为门禁；使用真实协议元数据，Mapper 只替代数据库边界。
 * 设计见 docs/superpowers/specs/2026-10-12-huasheng-fulfillment-hardening-design.md#3-映射管理契约。
 */
@ExtendWith(MockitoExtension.class)
class SupplierConfigurationMappingManagementTest {
    private static final long VERSION_ID = 99L;
    private static final long CONNECTOR_ID = 10L;
    private static final long PUBLISHED_VERSION_ID = 88L;
    @Mock private SupplyChainDatabaseClock databaseClock;
    @Mock private SupplierConnectorMapper connectorMapper;
    @Mock private SupplierConnectorVersionMapper versionMapper;
    @Mock private StatusMappingDefinitionMapper statusMappingMapper;
    @Mock private SupplierErrorCodeMapMapper errorCodeMapMapper;
    @Mock private AdapterMetadataRegistry metadataRegistry;
    @InjectMocks private SupplierConfigurationCommandService command;
    @InjectMocks private SupplierConfigurationQueryService query;

    @Test
    void huashengStatusAppendIsRejectedBeforeInsert() {
        draft(new HuashengOrderV1Metadata());
        assertThatThrownBy(() -> command.addStatusMapping(statusCommand()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("封闭模板");
        noInsert();
    }

    @Test
    void huashengErrorAppendIsRejectedBeforeInsert() {
        draft(new HuashengOrderV1Metadata());
        assertThatThrownBy(() -> command.addErrorCodeMapping(errorCommand("RETRY_SAFE", true)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("封闭模板");
        noInsert();
    }

    @Test
    void initializationRejectsAnExistingErrorOnlyTable() {
        draft(new HuashengOrderV1Metadata());
        when(errorCodeMapMapper.selectByConnectorVersionId(VERSION_ID))
                .thenReturn(List.of(SupplierErrorCodeMapDO.builder().build()));
        assertThatThrownBy(() -> command.initializeMappings(VERSION_ID, 1L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already been initialized");
        noInsert();
    }

    @Test
    void huashengInitializationPreservesTenNonterminalRowsAndNoErrors() {
        draft(new HuashengOrderV1Metadata());
        when(databaseClock.now()).thenReturn(LocalDateTime.of(2026, 10, 12, 12, 0));
        assertThat(command.initializeMappings(VERSION_ID, 1L)).isEqualTo(10);
        ArgumentCaptor<StatusMappingDefinitionDO> rows = ArgumentCaptor.forClass(StatusMappingDefinitionDO.class);
        verify(statusMappingMapper, times(10)).insert(rows.capture());
        assertThat(rows.getAllValues()).allSatisfy(row -> {
            assertThat(row.getMappingVersion()).isEqualTo(2);
            assertThat(row.getTerminalFlag()).isZero();
        });
        new HuashengOrderV1Metadata().validateMappings(rows.getAllValues(), List.of());
        verify(errorCodeMapMapper, never()).insert(any(SupplierErrorCodeMapDO.class));
    }

    @Test
    void qnmStatusAndErrorAppendRemainAvailable() {
        draft(new QnmGameOrderV1Metadata());
        when(databaseClock.now()).thenReturn(LocalDateTime.of(2026, 10, 12, 12, 0));
        command.addStatusMapping(statusCommand());
        command.addErrorCodeMapping(errorCommand("RETRY_SAFE", true));
        verify(statusMappingMapper).insert(any(StatusMappingDefinitionDO.class));
        ArgumentCaptor<SupplierErrorCodeMapDO> row = ArgumentCaptor.forClass(SupplierErrorCodeMapDO.class);
        verify(errorCodeMapMapper).insert(row.capture());
        assertThat(row.getValue().getRetryableFlag()).isEqualTo((short) 1);
    }

    @Test
    void qnmStillRejectsInconsistentRetryability() {
        when(versionMapper.selectByIdForUpdate(VERSION_ID)).thenReturn(version(ConnectorVersionStatus.DRAFT.getCode()));
        assertThatThrownBy(() -> command.addErrorCodeMapping(errorCommand("RETRY_SAFE", false)))
                .isInstanceOf(IllegalArgumentException.class);
        noInsert();
    }

    @Test
    void nonDraftVersionRejectsAllMappingWrites() {
        when(versionMapper.selectByIdForUpdate(VERSION_ID)).thenReturn(version(ConnectorVersionStatus.PUBLISHED.getCode()));
        assertThatThrownBy(() -> command.addStatusMapping(statusCommand())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> command.addErrorCodeMapping(errorCommand("RETRY_SAFE", true)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> command.initializeMappings(VERSION_ID, 1L)).isInstanceOf(IllegalStateException.class);
        noInsert();
    }

    @Test
    void missingConnectorRejectsBothAppendEntrypoints() {
        when(versionMapper.selectByIdForUpdate(VERSION_ID)).thenReturn(version(ConnectorVersionStatus.DRAFT.getCode()));
        assertThatThrownBy(() -> command.addStatusMapping(statusCommand())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> command.addErrorCodeMapping(errorCommand("RETRY_SAFE", true)))
                .isInstanceOf(IllegalStateException.class);
        noInsert();
    }

    @Test
    void huashengDraftProjectionIdentifiesViewedAndPublishedVersions() {
        draft(new HuashengOrderV1Metadata());
        JsonNode view = view();
        assertThat(view.path("connectorVersionId").asLong()).isEqualTo(VERSION_ID);
        assertThat(view.path("versionNo").asInt()).isEqualTo(2);
        assertThat(view.path("versionStatus").asInt()).isEqualTo(ConnectorVersionStatus.DRAFT.getCode());
        assertThat(view.path("currentPublishedVersionId").asLong()).isEqualTo(PUBLISHED_VERSION_ID);
        assertThat(view.path("initializeAllowed").asBoolean()).isTrue();
        assertThat(view.path("statusAppendAllowed").asBoolean()).isFalse();
        assertThat(view.path("errorAppendAllowed").asBoolean()).isFalse();
        assertThat(view.path("mappingDescription").asText()).contains("十行", "非终态", "错误", "资金");
    }

    @Test
    void qnmDraftProjectionAllowsBothAppends() {
        draft(new QnmGameOrderV1Metadata());
        JsonNode view = view();
        assertThat(view.path("initializeAllowed").asBoolean()).isTrue();
        assertThat(view.path("statusAppendAllowed").asBoolean()).isTrue();
        assertThat(view.path("errorAppendAllowed").asBoolean()).isTrue();
    }

    @Test
    void projectionDoesNotInitializeWithExistingStatusMappings() {
        draft(new QnmGameOrderV1Metadata());
        when(statusMappingMapper.selectByConnectorVersionId(VERSION_ID))
                .thenReturn(List.of(StatusMappingDefinitionDO.builder().id(5L).externalStatus("orderStatus=0").build()));
        JsonNode view = view();
        assertThat(view.path("initializeAllowed").asBoolean()).isFalse();
        assertThat(view.path("statusMappings").get(0).path("externalStatus").asText()).isEqualTo("orderStatus=0");
        assertThat(view.path("statusAppendAllowed").asBoolean()).isTrue();
    }

    @Test
    void projectionDoesNotInitializeWithExistingErrorMappings() {
        draft(new QnmGameOrderV1Metadata());
        when(errorCodeMapMapper.selectByConnectorVersionId(VERSION_ID))
                .thenReturn(List.of(SupplierErrorCodeMapDO.builder().id(6L).externalErrorCode("E_BUSY").build()));
        JsonNode view = view();
        assertThat(view.path("initializeAllowed").asBoolean()).isFalse();
        assertThat(view.path("errorCodeMappings").get(0).path("externalErrorCode").asText()).isEqualTo("E_BUSY");
        assertThat(view.path("errorAppendAllowed").asBoolean()).isTrue();
    }

    @Test
    void publishedProjectionDisallowsEveryMappingWrite() {
        draft(new QnmGameOrderV1Metadata());
        when(versionMapper.selectById(VERSION_ID)).thenReturn(version(ConnectorVersionStatus.PUBLISHED.getCode()));
        JsonNode view = view();
        assertThat(view.path("versionStatus").asInt()).isEqualTo(ConnectorVersionStatus.PUBLISHED.getCode());
        assertThat(view.path("initializeAllowed").asBoolean()).isFalse();
        assertThat(view.path("statusAppendAllowed").asBoolean()).isFalse();
        assertThat(view.path("errorAppendAllowed").asBoolean()).isFalse();
    }

    @Test
    void unknownVersionCannotReturnMappingManagementRules() {
        assertThatThrownBy(() -> query.getMappings(VERSION_ID)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingConnectorCannotReturnMappingManagementRules() {
        when(versionMapper.selectById(VERSION_ID)).thenReturn(version(ConnectorVersionStatus.DRAFT.getCode()));
        assertThatThrownBy(() -> query.getMappings(VERSION_ID)).isInstanceOf(IllegalArgumentException.class);
    }

    private void draft(AdapterMetadata metadata) {
        // 旧实现未读取追加策略/版本身份，lenient 仅用于让红测失败于缺少行为而非未使用桩。
        lenient().when(versionMapper.selectById(VERSION_ID)).thenReturn(version(ConnectorVersionStatus.DRAFT.getCode()));
        lenient().when(versionMapper.selectByIdForUpdate(VERSION_ID)).thenReturn(version(ConnectorVersionStatus.DRAFT.getCode()));
        lenient().when(versionMapper.updateById(any(SupplierConnectorVersionDO.class))).thenReturn(1);
        lenient().when(connectorMapper.selectById(CONNECTOR_ID)).thenReturn(SupplierConnectorDO.builder()
                .id(CONNECTOR_ID).adapterType(metadata.adapterType()).currentVersionId(PUBLISHED_VERSION_ID).build());
        lenient().when(metadataRegistry.require(metadata.adapterType())).thenReturn(metadata);
    }

    private SupplierConnectorVersionDO version(Short status) {
        return SupplierConnectorVersionDO.builder().id(VERSION_ID).connectorId(CONNECTOR_ID)
                .versionNo(2).status(status).version(0L).build();
    }

    private JsonNode view() {
        return new ObjectMapper().valueToTree(query.getMappings(VERSION_ID));
    }

    private SupplierConfigurationCommandService.AddStatusMappingCommand statusCommand() {
        return new SupplierConfigurationCommandService.AddStatusMappingCommand(
                VERSION_ID, "status=SUCCESS", "", "QUERY", "SUCCEEDED", true, 1L);
    }

    private SupplierConfigurationCommandService.AddErrorCodeMappingCommand errorCommand(String action, boolean retryable) {
        return new SupplierConfigurationCommandService.AddErrorCodeMappingCommand(
                VERSION_ID, "E_BUSY", "", "RETRYABLE", action, retryable, 1L);
    }

    private void noInsert() {
        verify(statusMappingMapper, never()).insert(any(StatusMappingDefinitionDO.class));
        verify(errorCodeMapMapper, never()).insert(any(SupplierErrorCodeMapDO.class));
    }
}
