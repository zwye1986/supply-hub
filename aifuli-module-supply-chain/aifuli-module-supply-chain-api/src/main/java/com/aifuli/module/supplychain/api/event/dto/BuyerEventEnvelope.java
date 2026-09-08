package com.aifuli.module.supplychain.api.event.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;

@Schema(description = "供应链中台投递给采购商商城的统一事件信封")
@Data
public class BuyerEventEnvelope implements Serializable {

    @Schema(description = "事件稳定业务号；重投必须保持不变", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "EVT_01K1XXXXXXXXXXXXXXXXXXXXX")
    @NotBlank(message = "事件编号不能为空")
    @Size(max = 32)
    private String eventId;

    @Schema(description = "事件类型，同时作为 data 的外部类型判别字段",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "ORDER_SUMMARY_CHANGED")
    @NotNull(message = "事件类型不能为空")
    private BuyerEventType eventType;

    @Schema(description = "事件 Schema 版本", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "事件 Schema 版本不能为空")
    @Positive(message = "事件 Schema 版本必须为正数")
    private Integer schemaVersion;

    @Schema(description = "事件发生时间", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-08-11T02:31:00Z")
    @NotNull(message = "事件发生时间不能为空")
    private OffsetDateTime occurredAt;

    @Schema(description = "本次投递链路追踪标识", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "trace-01K1XXXXXXXXXXXXXXXX")
    @NotBlank(message = "链路追踪标识不能为空")
    @Size(max = 64)
    private String traceId;

    @Schema(description = "跨异步步骤关联标识", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "corr-01K1XXXXXXXXXXXXXXXXX")
    @NotBlank(message = "关联标识不能为空")
    @Size(max = 64)
    private String correlationId;

    @Schema(description = "直接因果事件或命令标识", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "cause-01K1XXXXXXXXXXXXXXXX")
    @NotBlank(message = "因果标识不能为空")
    @Size(max = 64)
    private String causationId;

    @Schema(description = "由 eventType 判别的封闭事件载荷", requiredMode = Schema.RequiredMode.REQUIRED,
            oneOf = {OrderSummaryChangedEventData.class, FulfillmentResultEventData.class,
                    CatalogChangedEventData.class})
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXTERNAL_PROPERTY,
            property = "eventType")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = OrderSummaryChangedEventData.class, name = "ORDER_SUMMARY_CHANGED"),
            @JsonSubTypes.Type(value = FulfillmentResultEventData.class, name = "FULFILLMENT_RESULT"),
            @JsonSubTypes.Type(value = CatalogChangedEventData.class, name = "CATALOG_CHANGED")
    })
    @Valid
    @NotNull(message = "事件载荷不能为空")
    private BuyerEventData data;
}
