package com.aifuli.module.supplychain.api.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * 采购商目录读取快照，字段描述对应已发布商业版本及对外枚举，不返回供应商私有信息。
 * 设计见 docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#561-目录读取字段与注释契约。
 */
@Schema(description = "采购商可见的标准商品快照；不包含供应商、外部 SKU、供应成本或库存原始数量")
@Data
public class BuyerCatalogItemRespDTO implements Serializable {

    /** 采购商范围内的商品编码 */
    @Schema(description = "采购商范围内的商品编码", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "TELCO-100")
    private String buyerSkuCode;

    /** 采购商商品业务版本号，从1递增 */
    @Schema(description = "采购商商品业务版本号，从1递增", requiredMode = Schema.RequiredMode.REQUIRED, example = "42")
    private Long listingVersion;

    /** 平台SKU永久业务编码 */
    @Schema(description = "平台SKU永久业务编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "TELCO_100")
    private String platformSkuCode;

    /** 平台SPU永久业务编码 */
    @Schema(description = "平台SPU永久业务编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "TELCO_RECHARGE")
    private String platformSpuCode;

    /** 向采购商商城展示的商品名称 */
    @Schema(description = "向采购商商城展示的商品名称", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "话费直充 100 元")
    private String displayName;

    /** 平台品牌永久编码 */
    @Schema(description = "平台品牌永久编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "CMCC")
    private String brandCode;

    /** 品牌中文或标准名称 */
    @Schema(description = "品牌中文或标准名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "中国移动")
    private String brandName;

    /** 平台类目永久编码 */
    @Schema(description = "平台类目永久编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "DIRECT_RECHARGE")
    private String categoryCode;

    /** 类目名称 */
    @Schema(description = "类目名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "话费直充")
    private String categoryName;

    /** 履约类型：DIRECT_RECHARGE直充，VOUCHER_CODE券码，CARD_CODE_SECRET卡号卡密，VOUCHER_URL券链接，ACCOUNT_BOUND_BENEFIT账户绑定权益 */
    @Schema(description = "履约类型：DIRECT_RECHARGE直充，VOUCHER_CODE券码，CARD_CODE_SECRET卡号卡密，VOUCHER_URL券链接，ACCOUNT_BOUND_BENEFIT账户绑定权益", requiredMode = Schema.RequiredMode.REQUIRED, example = "DIRECT_RECHARGE")
    private String fulfillmentType;

    /** 采购商向中台结算的单位价格，单位为分 */
    @Schema(description = "采购商向中台结算的单位价格，单位为分", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "9800")
    private Long buyerSettlementUnitPrice;

    /** 结算币种，一期固定CNY */
    @Schema(description = "结算币种，一期固定CNY", requiredMode = Schema.RequiredMode.REQUIRED, example = "CNY")
    private String currency;

    /** 采购商商品生命周期：DRAFT草稿，PUBLISHED已发布，SUSPENDED暂停，RETIRED已退役 */
    @Schema(description = "采购商商品生命周期：DRAFT草稿，PUBLISHED已发布，SUSPENDED暂停，RETIRED已退役", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "PUBLISHED")
    private String listingStatus;

    /** 供给可用性：AVAILABLE可用，UNAVAILABLE不可用，UNKNOWN未知 */
    @Schema(description = "供给可用性：AVAILABLE可用，UNAVAILABLE不可用，UNKNOWN未知", requiredMode = Schema.RequiredMode.REQUIRED, example = "AVAILABLE")
    private String availabilityStatus;

    /** 相关可用性投影版本号 */
    @Schema(description = "相关可用性投影版本号", requiredMode = Schema.RequiredMode.REQUIRED, example = "7")
    private Long availabilityVersion;

    /** 采购商商品最后修改时间，UTC */
    @Schema(description = "采购商商品最后修改时间，UTC", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-08-11T02:30:00Z")
    private OffsetDateTime updatedAt;
}
