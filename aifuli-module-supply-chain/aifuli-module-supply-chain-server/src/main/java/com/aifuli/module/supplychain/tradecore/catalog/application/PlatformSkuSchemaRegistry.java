package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentTargetSchemaRegistry;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentResultSchemaCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTargetSchemaCode;
import com.aifuli.module.supplychain.tradecore.catalog.domain.FulfillmentType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 平台 SKU 履约类型到目标/结果 Schema 的唯一映射入口。
 *
 * <p>商品管理端只提交业务可理解的 {@link FulfillmentType}，Schema 编码与版本由本注册表生成并固化到 SKU，
 * 防止前端拼装出履约类型、目标结构和结果结构互相冲突的组合。新增履约类型或发布 Schema 新版本时必须先更新
 * {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d11）。</p>
 */
@Component
@RequiredArgsConstructor
public final class PlatformSkuSchemaRegistry {

    /** 当前一期已经发布的目标与结果 Schema 版本。 */
    private static final int VERSION_ONE = 1;

    private final FulfillmentTargetSchemaRegistry targetSchemaRegistry;

    /**
     * 按履约类型解析稳定的目标与结果契约，并再次校验目标 Schema 已在履约边界注册。
     */
    public SchemaDefinition requireByFulfillmentType(FulfillmentType fulfillmentType) {
        if (fulfillmentType == null) {
            throw new IllegalArgumentException("Fulfillment type is required");
        }
        SchemaDefinition definition = switch (fulfillmentType) {
            case DIRECT_RECHARGE -> schema(FulfillmentTargetSchemaCode.DIRECT_RECHARGE_TARGET,
                    FulfillmentResultSchemaCode.DIRECT_RECHARGE_RESULT);
            case VOUCHER_CODE -> schema(FulfillmentTargetSchemaCode.VOUCHER_DELIVERY_TARGET,
                    FulfillmentResultSchemaCode.VOUCHER_CODE_RESULT);
            case CARD_CODE_SECRET -> schema(FulfillmentTargetSchemaCode.VOUCHER_DELIVERY_TARGET,
                    FulfillmentResultSchemaCode.CARD_CODE_SECRET_RESULT);
            case VOUCHER_URL -> schema(FulfillmentTargetSchemaCode.VOUCHER_DELIVERY_TARGET,
                    FulfillmentResultSchemaCode.VOUCHER_URL_RESULT);
            case ACCOUNT_BOUND_BENEFIT -> schema(FulfillmentTargetSchemaCode.ACCOUNT_BOUND_BENEFIT_TARGET,
                    FulfillmentResultSchemaCode.ACCOUNT_BOUND_BENEFIT_RESULT);
        };
        targetSchemaRegistry.requireSupportedSchema(definition.targetSchemaCode(), definition.targetSchemaVersion());
        return definition;
    }

    private static SchemaDefinition schema(FulfillmentTargetSchemaCode target,
                                           FulfillmentResultSchemaCode result) {
        return new SchemaDefinition(target.name(), VERSION_ONE, result.name(), VERSION_ONE);
    }

    /** SKU 持久化使用的完整履约 Schema 快照。 */
    public record SchemaDefinition(String targetSchemaCode, Integer targetSchemaVersion,
                                   String resultSchemaCode, Integer resultSchemaVersion) {
    }
}
