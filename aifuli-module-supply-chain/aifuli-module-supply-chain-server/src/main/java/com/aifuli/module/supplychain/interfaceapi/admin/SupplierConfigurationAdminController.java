package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.common.util.monitor.TracerUtils;
import com.aifuli.module.supplychain.tradecore.supply.application.ConnectorPublicationService;
import com.aifuli.module.supplychain.tradecore.supply.application.ConnectorValidationService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierConfigurationCommandService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
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
    private final ConnectorValidationService validationService;
    private final ConnectorPublicationService publicationService;
    private final SupplierOfferPublicationService offerService;

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

    @PostMapping("/connectors/drafts")
    @Operation(summary = "创建连接器及首个草稿版本")
    public CommonResult<SupplierConfigurationCommandService.DraftConnectorResult> createConnectorDraft(
            @Valid @RequestBody CreateConnectorDraftReqVO request) {
        return success(configurationService.createConnectorDraft(
                new SupplierConfigurationCommandService.CreateConnectorDraftCommand(
                        request.getSupplierCode(), request.getConnectorCode(), request.getAdapterType(),
                        request.getBaseUrl(), request.getMerchantAccount(), request.getCredentialSlotNo(),
                        request.getProtocolVersion(), request.getCallbackPath(), request.getConnectTimeoutMs(),
                        request.getReadTimeoutMs(), request.getRateLimit(), request.getConcurrencyLimit(),
                        request.getAdapterConfigSchemaVersion(), request.getAdapterConfigJson(), getLoginUserId())));
    }

    @PostMapping("/connectors/qnm-status-mappings")
    @Operation(summary = "为奇那梦草稿写入一期封闭状态映射")
    public CommonResult<Integer> initializeQnmStatusMappings(
            @Valid @RequestBody InitializeQnmMappingsReqVO request) {
        return success(configurationService.initializeQnmStatusMappings(
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
    @Operation(summary = "发布供应商供给不可变版本")
    public CommonResult<SupplierOfferPublicationService.PublishResult> publishOffer(
            @Valid @RequestBody PublishOfferReqVO request) {
        return success(offerService.publish(new SupplierOfferPublicationService.PublishOfferCommand(
                request.getOfferNo(), request.getExpectedOfferVersion(), request.getExternalSkuCode(),
                request.getSupplierSettlementUnitPrice(), request.getConnectorVersionId(),
                request.getInventorySourceNo(), request.getMinQuantity(), request.getMaxQuantity(),
                request.getQuantityStep(), request.getPrimaryCapabilityCode(), request.getCompletionMode(),
                request.getCreateIdempotencyMode(), request.getUnknownResultPolicy(),
                request.getPartialDeliveryMode(), request.getResultMultiplicity(),
                request.getFulfillmentConfigSchemaVersion(), request.getFulfillmentConfigJson(),
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
    @Schema(description = "创建连接器草稿请求")
    public static class CreateConnectorDraftReqVO {
        @NotBlank private String supplierCode;
        @NotBlank @Size(max = 64) private String connectorCode;
        @NotBlank @Schema(description = "代码注册的适配器类型", example = "QNM_GAME_ORDER_V1")
        private String adapterType;
        @NotBlank @Size(max = 512) private String baseUrl;
        @Size(max = 128) private String merchantAccount;
        @NotBlank private String credentialSlotNo;
        @NotBlank @Size(max = 32) private String protocolVersion;
        @Size(max = 256) private String callbackPath;
        @Min(1) private int connectTimeoutMs;
        @Min(1) private int readTimeoutMs;
        @Min(1) private Integer rateLimit;
        @Min(1) private Integer concurrencyLimit;
        @Min(1) private int adapterConfigSchemaVersion;
        @NotBlank @Schema(description = "封闭 Schema JSON；奇那梦一期固定为 {}")
        private String adapterConfigJson;
    }

    @Data
    public static class InitializeQnmMappingsReqVO {
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
        @Min(1) private int fulfillmentConfigSchemaVersion;
        @NotBlank private String fulfillmentConfigJson;
        @NotNull private LocalDateTime validFrom;
        private LocalDateTime validTo;
        @NotBlank @Size(max = 512) private String publishReason;
    }
}
