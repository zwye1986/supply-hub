package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadata;
import com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadataRegistry;
import com.aifuli.module.supplychain.supplieradapter.metadata.Capability;
import com.aifuli.module.supplychain.supplieradapter.metadata.MappingTemplates;
import com.aifuli.module.supplychain.supplieradapter.metadata.StatusMappingDefinitionDO;
import com.aifuli.module.supplychain.supplieradapter.metadata.StatusMappingDefinitionMapper;
import com.aifuli.module.supplychain.supplieradapter.metadata.SupplierConnectorCapabilityDO;
import com.aifuli.module.supplychain.supplieradapter.metadata.SupplierConnectorCapabilityMapper;
import com.aifuli.module.supplychain.supplieradapter.metadata.SupplierErrorCodeMapDO;
import com.aifuli.module.supplychain.supplieradapter.metadata.SupplierErrorCodeMapMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialItemDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialSetDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialSlotDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialItemMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialSetMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialSlotMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.CredentialStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.ConnectorVersionStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 供应商履约技术配置菜单的只读投影服务（T1–T5）。
 *
 * <p>面向管理端「供应商履约技术配置」技术菜单，提供适配器目录、连接器列表、连接器版本详情、
 * 映射明细与凭据集列表五类只读投影。所有方法返回封闭投影记录，绝不暴露 DO 或凭据明文：
 * T4 凭据项投影只含 {@code itemKey/providerType/maskedHint/status}，
 * {@code secretRef} 值与 {@code activeSecretVersion} 一律不出现在返回结构中（F1-hybrid 录入只写不回显）。
 * 设计见
 * docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#61-技术菜单只读-get-面supplierconfigurationadmincontroller当前零-get。</p>
 */
@Service
@RequiredArgsConstructor
public class SupplierConfigurationQueryService {

    private final AdapterMetadataRegistry metadataRegistry;
    private final SupplierMapper supplierMapper;
    private final SupplierConnectorMapper connectorMapper;
    private final SupplierConnectorVersionMapper versionMapper;
    private final SupplierConnectorCapabilityMapper capabilityMapper;
    private final StatusMappingDefinitionMapper statusMappingMapper;
    private final SupplierErrorCodeMapMapper errorCodeMapMapper;
    private final CredentialSetMapper credentialSetMapper;
    private final CredentialItemMapper credentialItemMapper;
    private final CredentialSlotMapper credentialSlotMapper;

    /**
     * T1：适配器类型目录。来源于代码注册的封闭集 {@link AdapterMetadataRegistry#all()}，
     * 暴露能力、凭据键与映射模板计数供前端渲染表单，前端不能自由声明 adapterType。
     * 能力说明与编码均取自同一组注册能力，中文说明由后端枚举统一定义。
     * 设计见 docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#84-能力子页。
     */
    public List<AdapterTypeView> listAdapterTypes() {
        return metadataRegistry.all().stream().map(metadata -> {
            MappingTemplates templates = metadata.mappingTemplates();
            Set<Capability> capabilities = metadata.capabilities();
            return new AdapterTypeView(
                    metadata.adapterType(),
                    capabilities.stream().map(Enum::name).toList(),
                    List.copyOf(metadata.credentialKeys()),
                    templates.statusMappings().size(),
                    templates.errorCodeMappings().size(), metadata.credentialBindingMode().name(),
                    capabilities.stream().collect(
                            Collectors.toUnmodifiableMap(Capability::name, Capability::getDescription)));
        }).toList();
    }

    /**
     * T2：某供应商下的连接器列表投影（含当前已发布版本 id，null 表示尚未发布）。
     * 供应商不存在时返回空列表（只读、无副作用）。
     */
    public List<ConnectorListItem> listConnectors(String supplierCode) {
        SupplierDO supplier = supplierMapper.selectByCode(supplierCode);
        if (supplier == null) {
            return List.of();
        }
        return connectorMapper.selectList(SupplierConnectorDO::getSupplierId, supplier.getId()).stream()
                .map(connector -> {
                    SupplierConnectorVersionDO latest = latestVersion(connector.getId());
                    return new ConnectorListItem(
                            connector.getConnectorNo(), connector.getAdapterType(),
                            connector.getConnectorCode(), connector.getCurrentVersionId(),
                            connector.getStatus(),
                            latest == null ? null : latest.getId(),
                            latest == null ? null : latest.getVersionNo(),
                            latest == null ? null : latest.getStatus());
                })
                .toList();
    }

    /**
     * 取连接器最新版本（Phase 1 连接器与版本 1:1，按 versionNo 取最大以兼容未来多版本）。
     * 供 T2 暴露 latestVersionId/No/Status，使前端刷新后仍能驱动草稿/已验证态的生命周期。
     */
    private SupplierConnectorVersionDO latestVersion(Long connectorId) {
        return versionMapper.selectList(SupplierConnectorVersionDO::getConnectorId, connectorId).stream()
                .max(java.util.Comparator.comparing(SupplierConnectorVersionDO::getVersionNo))
                .orElse(null);
    }

    /**
     * T3：连接器版本详情投影（公共连接参数 + 适配器配置 JSON + 能力启用态）。
     * 不含凭据明文；凭据以引用槽 id 形式在写侧管理，读侧只回显非秘密参数。
     */
    public ConnectorVersionDetail getConnectorVersionDetail(String connectorNo, Long versionId) {
        SupplierConnectorDO connector = connectorMapper.selectByNo(connectorNo);
        if (connector == null) {
            throw new IllegalArgumentException("Connector does not exist: " + connectorNo);
        }
        SupplierConnectorVersionDO version = versionMapper.selectById(versionId);
        if (version == null || !version.getConnectorId().equals(connector.getId())) {
            throw new IllegalArgumentException(
                    "Connector version does not belong to connector: " + connectorNo + "#" + versionId);
        }
        List<CapabilityView> capabilities = capabilityMapper.selectByConnectorVersionId(versionId).stream()
                .map(capability -> new CapabilityView(
                        capability.getCapabilityCode(),
                        capability.getEnabled() != null && capability.getEnabled() == 1))
                .toList();
        CredentialSlotDO slot = version.getCredentialSlotId() == null ? null : credentialSlotMapper.selectById(version.getCredentialSlotId());
        CredentialSetDO set = version.getCredentialSetId() == null ? null : credentialSetMapper.selectById(version.getCredentialSetId());
        return new ConnectorVersionDetail(
                version.getId(), connector.getConnectorNo(), connector.getAdapterType(),
                version.getVersionNo(), version.getStatus(), version.getBaseUrl(),
                version.getMerchantAccount(), version.getProtocolVersion(), version.getCallbackPath(),
                version.getConnectTimeoutMs(), version.getReadTimeoutMs(), version.getRateLimit(),
                version.getConcurrencyLimit(), version.getAdapterConfigSchemaVersion(),
                version.getAdapterConfigJson(), version.getExtraConfig(),
                version.getValidationStatus(), version.getPublishedAt(), capabilities,
                metadataRegistry.require(connector.getAdapterType()).credentialBindingMode().name(),
                slot == null ? null : slot.getSlotNo(), set == null ? null : set.getSetCode());
    }

    /**
     * T5：映射明细、查看版本身份与适配器管理约束。
     * 缺少版本或连接器身份直接拒绝；仅草稿可写，初始化还要求两表均空，追加由真实适配器声明。
     * allowed 是当前可操作性投影，写入口仍独立核验，不替代 HTTP 权限。
     * 设计见 docs/superpowers/specs/2026-10-12-huasheng-fulfillment-hardening-design.md#31-后端元数据与查询。
     */
    public MappingsView getMappings(Long versionId) {
        SupplierConnectorVersionDO version = versionMapper.selectById(versionId);
        if (version == null || version.getConnectorId() == null) {
            throw new IllegalArgumentException("Connector version does not exist: " + versionId);
        }
        SupplierConnectorDO connector = connectorMapper.selectById(version.getConnectorId());
        if (connector == null) {
            throw new IllegalArgumentException("Connector does not exist for version: " + versionId);
        }
        AdapterMetadata metadata = metadataRegistry.require(connector.getAdapterType());
        List<StatusMappingView> statusMappings = statusMappingMapper.selectByConnectorVersionId(versionId).stream()
                .map(SupplierConfigurationQueryService::toStatusView).toList();
        List<ErrorCodeMappingView> errorCodeMappings =
                errorCodeMapMapper.selectByConnectorVersionId(versionId).stream()
                        .map(SupplierConfigurationQueryService::toErrorView).toList();
        boolean draft = Short.valueOf(ConnectorVersionStatus.DRAFT.getCode()).equals(version.getStatus());
        return new MappingsView(statusMappings, errorCodeMappings,
                version.getId(), version.getVersionNo(), version.getStatus(), connector.getCurrentVersionId(),
                draft && statusMappings.isEmpty() && errorCodeMappings.isEmpty(),
                draft && metadata.statusMappingAppendAllowed(), draft && metadata.errorMappingAppendAllowed(),
                metadata.mappingDescription());
    }

    /**
     * T4：某供应商的凭据集与凭据项列表投影。凭据项只回显
     * {@code itemKey/providerType/maskedHint/status}，绝不返回 {@code secretRef} 值或密文。
     */
    public List<CredentialSetView> listCredentialSets(String supplierCode) {
        SupplierDO supplier = supplierMapper.selectByCode(supplierCode);
        if (supplier == null) {
            return List.of();
        }
        return credentialSetMapper.selectList(CredentialSetDO::getSupplierId, supplier.getId()).stream()
                .map(set -> new CredentialSetView(
                        set.getId(), set.getSetCode(), set.getStatus(),
                        credentialItemMapper.selectListBySetId(set.getId()).stream()
                                .map(SupplierConfigurationQueryService::toCredentialItemView).toList()))
                .toList();
    }

    /**
     * A1（§8.2 连接器「凭据引用下拉」数据源）：只读列出可用（status=ACTIVE）的凭据槽，供连接器草稿绑定
     * {@code credentialSlotNo}。凭据槽为平台级（{@code sc_credential_slot} 无 {@code supplier_id}），故不按供应商过滤。
     * 投影脱敏：只回 {@code slotNo/providerType/maskedHint/status}，绝不返回 {@code secretRef} 值或密文。
     * 设计见 docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#82-连接器子页e1。
     */
    public List<CredentialSlotView> listActiveCredentialSlots() {
        return credentialSlotMapper.selectList(CredentialSlotDO::getStatus, CredentialStatus.ACTIVE.getCode()).stream()
                .map(slot -> new CredentialSlotView(slot.getSlotNo(), slot.getProviderType(),
                        slot.getMaskedHint(), slot.getStatus()))
                .toList();
    }

    private static StatusMappingView toStatusView(StatusMappingDefinitionDO mapping) {
        return new StatusMappingView(mapping.getId(), mapping.getExternalStatus(),
                mapping.getExternalSubStatus(), mapping.getBusinessPhase(), mapping.getFactType(),
                mapping.getTerminalFlag() != null && mapping.getTerminalFlag() == 1,
                mapping.getPriority(), mapping.getStatus());
    }

    private static ErrorCodeMappingView toErrorView(SupplierErrorCodeMapDO mapping) {
        return new ErrorCodeMappingView(mapping.getId(), mapping.getExternalErrorCode(),
                mapping.getExternalSubCode(), mapping.getErrorClass(), mapping.getActionType(),
                mapping.getRetryableFlag() != null && mapping.getRetryableFlag() == 1,
                mapping.getStatus());
    }

    private static CredentialItemView toCredentialItemView(CredentialItemDO item) {
        return new CredentialItemView(item.getId(), item.getItemKey(), item.getProviderType(),
                item.getMaskedHint(), item.getStatus());
    }

    /**
     * T1 适配器类型目录项。capabilities/credentialKeys 为封闭集字符串，计数来自映射模板。
     * capabilityDescriptions 与 capabilities 的编码集合一致，值为后端枚举定义的中文名称与职责解释。
     * 设计见 docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#84-能力子页。
     */
    public record AdapterTypeView(String adapterType, List<String> capabilities,
                                  List<String> credentialKeys, int statusMappingTemplateCount,
                                  int errorCodeMappingTemplateCount, String credentialBindingMode,
                                  Map<String, String> capabilityDescriptions) {
    }

    /**
     * T2 连接器列表项。currentVersionId 为 null 表示该连接器尚无已发布版本。
     * latestVersionId/latestVersionNo/latestVersionStatus 暴露该连接器最新版本（Phase 1 连接器与版本 1:1），
     * 使前端在刷新后仍能对草稿/已验证态驱动 校验→发布→退役（草稿态 currentVersionId 为 null）。
     */
    public record ConnectorListItem(String connectorNo, String adapterType, String connectorCode,
                                    Long currentVersionId, Short status,
                                    Long latestVersionId, Integer latestVersionNo, Short latestVersionStatus) {
    }

    /** T3 连接器版本详情。仅含非秘密连接参数与能力启用态。 */
    public record ConnectorVersionDetail(Long versionId, String connectorNo, String adapterType,
                                         Integer versionNo, Short status, String baseUrl,
                                         String merchantAccount, String protocolVersion, String callbackPath,
                                         Integer connectTimeoutMs, Integer readTimeoutMs, Integer rateLimit,
                                         Integer concurrencyLimit, Integer adapterConfigSchemaVersion,
                                         String adapterConfigJson, String extraConfig,
                                         Short validationStatus, java.time.LocalDateTime publishedAt,
                                         List<CapabilityView> capabilities, String credentialBindingMode,
                                         String credentialSlotNo, String credentialSetCode) {
    }

    /** T3 能力启用态。capabilityCode 落在 {@link com.aifuli.module.supplychain.supplieradapter.metadata.Capability} 封闭集。 */
    public record CapabilityView(String capabilityCode, boolean enabled) {
    }

    /**
     * T5 映射明细与管理投影。
     * connectorVersionId：连接器版本主键，逻辑引用sc_supplier_connector_version.id；
     * versionNo：连接器业务版本号，从1递增；versionStatus：生命周期：0草稿，5已验证，10已发布，90已退役；
     * currentPublishedVersionId：当前已发布连接器版本主键。
     * initializeAllowed/statusAppendAllowed/errorAppendAllowed 为服务端当前可操作性，mappingDescription 为中文协议说明。
     * 设计见 docs/superpowers/specs/2026-10-12-huasheng-fulfillment-hardening-design.md#3-映射管理契约。
     */
    public record MappingsView(List<StatusMappingView> statusMappings,
                               List<ErrorCodeMappingView> errorCodeMappings,
                               Long connectorVersionId, Integer versionNo, Short versionStatus,
                               @JsonInclude(JsonInclude.Include.ALWAYS) Long currentPublishedVersionId, boolean initializeAllowed,
                               boolean statusAppendAllowed, boolean errorAppendAllowed, String mappingDescription) {
        /** 兼容旧两列表调用；缺少版本与适配器身份时一律不可写。 */
        public MappingsView(List<StatusMappingView> statusMappings, List<ErrorCodeMappingView> errorCodeMappings) {
            this(statusMappings, errorCodeMappings, null, null, null, null, false, false, false, null);
        }
    }

    /** T5 单条状态映射投影。 */
    public record StatusMappingView(Long id, String externalStatus, String externalSubStatus,
                                    String businessPhase, String factType, boolean terminal,
                                    Integer priority, Short status) {
    }

    /** T5 单条错误码映射投影。 */
    public record ErrorCodeMappingView(Long id, String externalErrorCode, String externalSubCode,
                                       String errorClass, String actionType, boolean retryable,
                                       Short status) {
    }

    /** T4 凭据集投影，含其下凭据项列表。 */
    public record CredentialSetView(Long id, String setCode, Short status,
                                    List<CredentialItemView> items) {
    }

    /**
     * T4 凭据项投影：只含非秘密字段。{@code secretRef} 与 {@code activeSecretVersion} 被刻意排除，
     * 保证序列化结果永不出现密文或 {@code env:} 引用（F1-hybrid 只写不回显）。
     */
    public record CredentialItemView(Long id, String itemKey, String providerType,
                                     String maskedHint, Short status) {
    }

    /**
     * A1 凭据槽只读投影（连接器「凭据引用下拉」数据源）：只含非秘密字段。
     * {@code secretRef} 与 {@code activeSecretVersion} 被刻意排除，保证序列化结果永不出现 {@code env:} 引用或密文。
     */
    public record CredentialSlotView(String slotNo, String providerType, String maskedHint, Short status) {
    }
}
