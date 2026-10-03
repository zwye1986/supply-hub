package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.common.util.monitor.TracerUtils;
import com.aifuli.module.supplychain.tradecore.supply.application.ConnectorPublicationService;
import com.aifuli.module.supplychain.tradecore.supply.application.ConnectorValidationService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierConfigurationCommandService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierConfigurationQueryService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierCredentialCommandService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierOfferPublicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.aifuli.framework.common.pojo.CommonResult.success;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.ADMIN_EXPRESSION;
import static com.aifuli.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

@Tag(name = "管理后台 - 供应链供应商与履约配置")
@RestController
@RequestMapping("/supply-chain/supplier-config")
@Validated
@RequiredArgsConstructor
@PreAuthorize(ADMIN_EXPRESSION)
public class SupplierConfigurationAdminController {

    private final SupplierConfigurationCommandService configurationService;
    private final SupplierConfigurationQueryService queryService;
    private final SupplierCredentialCommandService credentialService;
    private final ConnectorValidationService validationService;
    private final ConnectorPublicationService publicationService;
    private final SupplierOfferPublicationService offerService;

    @GetMapping("/adapter-types")
    @Operation(summary = "列出代码注册的适配器类型目录",
            description = "技术菜单 T1：封闭集，暴露能力/凭据键/映射模板计数供前端渲染。")
    public CommonResult<List<SupplierConfigurationQueryService.AdapterTypeView>> listAdapterTypes() {
        return success(queryService.listAdapterTypes());
    }

    @GetMapping("/{supplierCode}/connectors")
    @Operation(summary = "列出某供应商的连接器",
            description = "技术菜单 T2：含当前已发布版本 id，null 表示尚未发布。")
    public CommonResult<List<SupplierConfigurationQueryService.ConnectorListItem>> listConnectors(
            @PathVariable("supplierCode") String supplierCode) {
        return success(queryService.listConnectors(supplierCode));
    }

    @GetMapping("/connectors/{connectorNo}/versions/{versionId}")
    @Operation(summary = "查询连接器版本详情",
            description = "技术菜单 T3：非秘密连接参数 + 适配器配置 + 能力启用态，不含凭据明文。")
    public CommonResult<SupplierConfigurationQueryService.ConnectorVersionDetail> getConnectorVersionDetail(
            @PathVariable("connectorNo") String connectorNo,
            @PathVariable("versionId") Long versionId) {
        return success(queryService.getConnectorVersionDetail(connectorNo, versionId));
    }

    @GetMapping("/{supplierCode}/credential-sets")
    @Operation(summary = "列出某供应商的凭据集与凭据项",
            description = "技术菜单 T4：凭据项只返回 itemKey/providerType/maskedHint/status，绝不返回 secretRef 或密文。")
    public CommonResult<List<SupplierConfigurationQueryService.CredentialSetView>> listCredentialSets(
            @PathVariable("supplierCode") String supplierCode) {
        return success(queryService.listCredentialSets(supplierCode));
    }

    @GetMapping("/credential-slots")
    @Operation(summary = "列出可用凭据槽（连接器「凭据引用下拉」数据源）",
            description = "技术菜单 A1（§8.2）：凭据槽为平台级（sc_credential_slot 无 supplier_id），只回 "
                    + "slotNo/providerType/maskedHint/status，绝不返回 secret_ref 值或密文（F1-hybrid 只写不回显）。")
    public CommonResult<List<SupplierConfigurationQueryService.CredentialSlotView>> listCredentialSlots() {
        return success(queryService.listActiveCredentialSlots());
    }

    @GetMapping("/connectors/versions/{versionId}/mappings")
    @Operation(summary = "查询连接器版本的状态与错误码映射明细", description = "技术菜单 T5。")
    public CommonResult<SupplierConfigurationQueryService.MappingsView> getMappings(
            @PathVariable("versionId") Long versionId) {
        return success(queryService.getMappings(versionId));
    }

    @PostMapping("/suppliers")
    @Operation(summary = "创建供应商主体")
    public CommonResult<String> createSupplier(@Valid @RequestBody CreateSupplierReqVO request) {
        return success(configurationService.createSupplier(
                new SupplierConfigurationCommandService.CreateSupplierCommand(
                        request.getSupplierCode(), request.getSupplierName(), request.getShortName(),
                        request.getSupplierType(), request.getLegalEntityName(), getLoginUserId())));
    }

    @PostMapping("/credential-slots")
    @Operation(summary = "创建供应商凭证引用槽", description = "只接受 env:NAME 等受控引用，不接收或返回秘密明文")
    public CommonResult<String> createCredentialSlot(@Valid @RequestBody CreateCredentialSlotReqVO request) {
        return success(configurationService.createCredentialSlot(
                new SupplierConfigurationCommandService.CreateCredentialSlotCommand(
                        "ENV", request.getSecretRef(), request.getMaskedHint(),
                        request.getActiveSecretVersion(), getLoginUserId())));
    }

    @PostMapping("/credential-sets")
    @Operation(summary = "创建供应商凭据集", description = "技术菜单 §6.3：供应商内 setCode 唯一，初始可用。")
    public CommonResult<Long> createCredentialSet(@Valid @RequestBody CreateCredentialSetReqVO request) {
        return success(credentialService.createCredentialSet(
                new SupplierCredentialCommandService.CreateCredentialSetCommand(
                        request.getSupplierCode(), request.getSetCode(), getLoginUserId())));
    }

    @PostMapping("/credential-items")
    @Operation(summary = "向凭据集添加凭据项",
            description = "技术菜单 §6.3（F1-hybrid）：ENV 只录 env: 引用；ENVELOPE 录 secretValue 明文，"
                    + "服务端立即信封加密入库，secretValue 仅入参、不回显、不落日志。")
    public CommonResult<Boolean> createCredentialItem(@Valid @RequestBody CreateCredentialItemReqVO request) {
        credentialService.addCredentialItem(
                new SupplierCredentialCommandService.AddCredentialItemCommand(
                        request.getSupplierCode(), request.getSetCode(), request.getItemKey(),
                        request.getProviderType(), request.getSecretRef(), request.getSecretValue(),
                        request.getMaskedHint(), request.getActiveSecretVersion(), getLoginUserId()));
        return success(true);
    }

    @PostMapping("/connectors/drafts")
    @Operation(summary = "创建连接器及首个草稿版本",
            description = "适配器配置 Schema 版本由服务端按 adapterType 生成。")
    public CommonResult<SupplierConfigurationCommandService.DraftConnectorResult> createConnectorDraft(
            @Valid @RequestBody CreateConnectorDraftReqVO request) {
        return success(configurationService.createConnectorDraft(
                new SupplierConfigurationCommandService.CreateConnectorDraftCommand(
                        request.getSupplierCode(), request.getConnectorCode(), request.getAdapterType(),
                        request.getBaseUrl(), request.getMerchantAccount(), request.getCredentialSlotNo(),
                        request.getProtocolVersion(), request.getCallbackPath(), request.getConnectTimeoutMs(),
                        request.getReadTimeoutMs(), request.getRateLimit(), request.getConcurrencyLimit(),
                        request.getAdapterConfigJson(), getLoginUserId(), request.getCredentialSetCode(), request.getExtraConfig())));
    }

    @PostMapping("/connectors/mappings/initialize")
    @Operation(summary = "按适配器模板初始化连接器草稿的状态与错误码映射",
            description = "适配器无关：从该版本 adapterType 的 mappingTemplates() 播种（G2）。")
    public CommonResult<Integer> initializeMappings(
            @Valid @RequestBody InitializeMappingsReqVO request) {
        return success(configurationService.initializeMappings(
                request.getConnectorVersionId(), getLoginUserId()));
    }

    @PostMapping("/connectors/error-mappings")
    @Operation(summary = "向连接器草稿添加封闭错误码映射")
    public CommonResult<Boolean> addErrorMapping(@Valid @RequestBody AddErrorMappingReqVO request) {
        configurationService.addErrorCodeMapping(
                new SupplierConfigurationCommandService.AddErrorCodeMappingCommand(
                        request.getConnectorVersionId(), request.getExternalErrorCode(),
                        request.getExternalSubCode(), request.getErrorClass(), request.getActionType(),
                        request.getRetryable(), getLoginUserId()));
        return success(true);
    }

    @PostMapping("/connectors/status-mappings")
    @Operation(summary = "向连接器草稿追加单条封闭状态映射")
    public CommonResult<Boolean> addStatusMapping(@Valid @RequestBody AddStatusMappingReqVO request) {
        configurationService.addStatusMapping(
                new SupplierConfigurationCommandService.AddStatusMappingCommand(
                        request.getConnectorVersionId(), request.getExternalStatus(),
                        request.getExternalSubStatus(), request.getBusinessPhase(),
                        request.getFactType(), request.getTerminal(), getLoginUserId()));
        return success(true);
    }

    @PostMapping("/connectors/validate")
    @Operation(summary = "在业务事务外验证连接器草稿")
    public CommonResult<ConnectorValidationService.ValidationResult> validateConnector(
            @Valid @RequestBody ValidateConnectorReqVO request) {
        return success(validationService.validate(new ConnectorValidationService.ValidateConnectorCommand(
                request.getConnectorVersionId(), request.getCapabilities(),
                Duration.ofMinutes(request.getValidMinutes()), getLoginUserId(),
                request.getReason(), traceId())));
    }

    @PostMapping("/connectors/publish")
    @Operation(summary = "发布已验证的连接器不可变版本")
    public CommonResult<ConnectorPublicationService.PublishResult> publishConnector(
            @Valid @RequestBody PublishConnectorReqVO request) {
        return success(publicationService.publish(new ConnectorPublicationService.PublishConnectorCommand(
                request.getConnectorNo(), request.getConnectorVersionId(), request.getExpectedConnectorVersion(),
                getLoginUserId(), request.getReason(), traceId(), null, null)));
    }

    @PutMapping("/connectors/retire")
    @Operation(summary = "退役连接器已发布版本")
    public CommonResult<Boolean> retireConnector(@Valid @RequestBody RetireConnectorReqVO request) {
        publicationService.retire(new ConnectorPublicationService.RetireConnectorVersionCommand(
                request.getConnectorNo(), request.getConnectorVersionId(), getLoginUserId(),
                request.getReason(), traceId(), null, null));
        return success(true);
    }

    @PostMapping("/inventory-sources/remote-on-demand")
    @Operation(summary = "创建远程按需库存来源草稿")
    public CommonResult<String> createInventorySource(@Valid @RequestBody CreateInventorySourceReqVO request) {
        return success(offerService.createRemoteOnDemandInventory(
                new SupplierOfferPublicationService.CreateInventorySourceCommand(
                        request.getSupplierCode(), request.getPlatformSkuCode(),
                        request.getExternalSourceCode(), getLoginUserId())));
    }

    @PutMapping("/inventory-sources/activate")
    @Operation(summary = "启用远程按需库存来源")
    public CommonResult<Boolean> activateInventorySource(@Valid @RequestBody ActivateInventorySourceReqVO request) {
        offerService.activateInventorySource(request.getSourceNo(), request.getExpectedVersion(), getLoginUserId());
        return success(true);
    }

    @PostMapping("/offers")
    @Operation(summary = "创建供应商供给草稿")
    public CommonResult<String> createOffer(@Valid @RequestBody CreateOfferReqVO request) {
        return success(offerService.createOffer(new SupplierOfferPublicationService.CreateOfferCommand(
                request.getSupplierCode(), request.getPlatformSkuCode(),
                request.getOfferCode(), getLoginUserId())));
    }

    @PostMapping("/offers/publish")
    @Operation(summary = "发布供应商供给不可变版本",
            description = "履约配置 Schema 版本由服务端按连接器 adapterType 生成。")
    public CommonResult<SupplierOfferPublicationService.PublishResult> publishOffer(
            @Valid @RequestBody PublishOfferReqVO request) {
        return success(offerService.publish(new SupplierOfferPublicationService.PublishOfferCommand(
                request.getOfferNo(), request.getExpectedOfferVersion(), request.getExternalSkuCode(),
                request.getSupplierSettlementUnitPrice(), request.getConnectorVersionId(),
                request.getInventorySourceNo(), request.getMinQuantity(), request.getMaxQuantity(),
                request.getQuantityStep(), request.getPrimaryCapabilityCode(), request.getCompletionMode(),
                request.getCreateIdempotencyMode(), request.getUnknownResultPolicy(),
                request.getPartialDeliveryMode(), request.getResultMultiplicity(),
                request.getFulfillmentConfigJson(),
                request.getValidFrom(), request.getValidTo(), request.getPublishReason(),
                getLoginUserId(), traceId(), null, null)));
    }

    private static String traceId() {
        String traceId = TracerUtils.getTraceId();
        return traceId == null || traceId.isBlank() ? "admin-" + UUID.randomUUID() : traceId;
    }

    @Data
    @Schema(description = "创建供应商请求")
    public static class CreateSupplierReqVO {
        @NotBlank @Size(max = 32) private String supplierCode;
        @NotBlank @Size(max = 128) private String supplierName;
        @NotBlank @Size(max = 64) private String shortName;
        @NotBlank @Schema(description = "STANDARD_SUPPLIER 或 O2O_MERCHANT") private String supplierType;
        @Size(max = 256) private String legalEntityName;
    }

    @Data
    @Schema(description = "创建凭证引用槽请求")
    public static class CreateCredentialSlotReqVO {
        @NotBlank @Size(max = 512) @Schema(description = "秘密引用，例如 env:QNM_USERKEY")
        private String secretRef;
        @Size(max = 128) private String maskedHint;
        @NotBlank @Size(max = 64) private String activeSecretVersion;
    }

    @Data
    @Schema(description = "创建凭据集请求")
    public static class CreateCredentialSetReqVO {
        @NotBlank private String supplierCode;
        @NotBlank @Size(max = 64) @Schema(description = "凭据集编号，供应商内唯一") private String setCode;
    }

    @Data
    @Schema(description = "创建凭据项请求（F1-hybrid）")
    public static class CreateCredentialItemReqVO {
        @NotBlank private String supplierCode;
        @NotBlank @Size(max = 64) private String setCode;
        @NotBlank @Size(max = 64)
        @Schema(description = "凭据项键，须在适配器 credentialKeys() 并集内，如 secretKey") private String itemKey;
        @NotBlank @Schema(description = "ENV 或 ENVELOPE") private String providerType;
        @Size(max = 512) @Schema(description = "ENV 时的秘密引用，须以 env: 前缀；ENVELOPE 时留空")
        private String secretRef;
        @Schema(description = "ENVELOPE 时的秘密明文，仅入参、服务端立即信封加密、不回显不落日志；ENV 时留空",
                accessMode = Schema.AccessMode.WRITE_ONLY)
        @Size(max = 8192) @lombok.ToString.Exclude private String secretValue;
        @Size(max = 128) private String maskedHint;
        @NotNull private Integer activeSecretVersion;
    }

    @Data
    @Schema(description = "创建连接器草稿请求")
    public static class CreateConnectorDraftReqVO {
        @NotBlank private String supplierCode;
        @NotBlank @Size(max = 64) private String connectorCode;
        @NotBlank @Schema(description = "代码注册的适配器类型", example = "QNM_GAME_ORDER_V1")
        private String adapterType;
        @NotBlank @Size(max = 512) private String baseUrl;
        @Size(max = 128) private String merchantAccount;
        /** 凭证槽编号，SINGLE_SLOT 模式条件必填，与凭据集编号互斥。 */
        private String credentialSlotNo;
        /** 凭据集编号，供应商内唯一；CREDENTIAL_SET 模式条件必填。 */
        @Size(max = 64) private String credentialSetCode;
        /** 连接器版本非秘密可调参数，如expireTime秒、outMobile；密钥类禁止入此列 */
        private String extraConfig;
        @NotBlank @Size(max = 32) private String protocolVersion;
        @Size(max = 256) private String callbackPath;
        @Min(1) private int connectTimeoutMs;
        @Min(1) private int readTimeoutMs;
        @Min(1) private Integer rateLimit;
        @Min(1) private Integer concurrencyLimit;
        @NotBlank @Schema(description = "封闭 Schema JSON；奇那梦一期固定为 {}")
        private String adapterConfigJson;
    }

    @Data
    public static class InitializeMappingsReqVO {
        @NotNull private Long connectorVersionId;
    }

    @Data
    public static class AddErrorMappingReqVO {
        @NotNull private Long connectorVersionId;
        @NotBlank @Size(max = 64) private String externalErrorCode;
        @Size(max = 64) private String externalSubCode;
        @NotBlank private String errorClass;
        @NotBlank @Schema(description = "REJECT、RETRY_SAFE、QUERY_FIRST 或 MANUAL")
        private String actionType;
        @NotNull private Boolean retryable;
    }

    @Data
    public static class AddStatusMappingReqVO {
        @NotNull private Long connectorVersionId;
        @NotBlank @Size(max = 128) private String externalStatus;
        @Size(max = 128) private String externalSubStatus;
        @NotBlank @Schema(description = "CREATE、QUERY 或 CALLBACK")
        private String businessPhase;
        @NotBlank @Schema(description = "ACCEPTED、SUCCEEDED、REJECTED 或 RESULT_UNKNOWN")
        private String factType;
        @NotNull private Boolean terminal;
    }

    @Data
    public static class ValidateConnectorReqVO {
        @NotNull private Long connectorVersionId;
        @NotEmpty private Set<String> capabilities;
        @Min(1) private int validMinutes;
        @NotBlank @Size(max = 512) private String reason;
    }

    @Data
    public static class PublishConnectorReqVO {
        @NotBlank private String connectorNo;
        @NotNull private Long connectorVersionId;
        @Min(0) private long expectedConnectorVersion;
        @NotBlank @Size(max = 512) private String reason;
    }

    @Data
    public static class RetireConnectorReqVO {
        @NotBlank private String connectorNo;
        @NotNull private Long connectorVersionId;
        @NotBlank @Size(max = 512) private String reason;
    }

    @Data
    public static class CreateInventorySourceReqVO {
        @NotBlank private String supplierCode;
        @NotBlank private String platformSkuCode;
        @Size(max = 128) private String externalSourceCode;
    }

    @Data
    public static class ActivateInventorySourceReqVO {
        @NotBlank private String sourceNo;
        @Min(0) private long expectedVersion;
    }

    @Data
    public static class CreateOfferReqVO {
        @NotBlank private String supplierCode;
        @NotBlank private String platformSkuCode;
        @NotBlank @Size(max = 64) private String offerCode;
    }

    @Data
    public static class PublishOfferReqVO {
        @NotBlank private String offerNo;
        @Min(0) private long expectedOfferVersion;
        @NotBlank @Size(max = 128) private String externalSkuCode;
        @Min(0) private long supplierSettlementUnitPrice;
        @NotNull private Long connectorVersionId;
        @NotBlank private String inventorySourceNo;
        @Min(1) private long minQuantity;
        @Min(1) private long maxQuantity;
        @Min(1) private long quantityStep;
        @NotBlank private String primaryCapabilityCode;
        @NotBlank private String completionMode;
        @NotBlank private String createIdempotencyMode;
        @NotBlank private String unknownResultPolicy;
        @NotBlank private String partialDeliveryMode;
        @NotBlank private String resultMultiplicity;
        @NotBlank private String fulfillmentConfigJson;
        @NotNull private LocalDateTime validFrom;
        private LocalDateTime validTo;
        @NotBlank @Size(max = 512) private String publishReason;
    }
}
