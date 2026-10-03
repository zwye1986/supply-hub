package com.aifuli.module.supplychain.api.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;

@Schema(description = "采购商可见的标准商品快照；不包含供应商、外部 SKU、供应成本或库存原始数量")
@Data
public class BuyerCatalogItemRespDTO implements Serializable {

    /** 采购商范围内的商品编码 */
    @Schema(description = "采购商范围内的商品编码", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "TELCO-100")
    private String buyerSkuCode;

    /** 当前已发布采购商商品版本主键 */
    @Schema(description = "当前已发布采购商商品版本主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "42")
    private Long listingVersion;

    /** 平台SKU永久业务编码 */
    @Schema(description = "平台SKU永久业务编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "TELCO_100")
    private String platformSkuCode;

    /** 平台SPU永久业务编码 */
    @Schema(description = "平台SPU永久业务编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "TELCO_RECHARGE")
    private String platformSpuCode;

    /** 平台SKU标准名称 */
    @Schema(description = "平台SKU标准名称", requiredMode = Schema.RequiredMode.REQUIRED,
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

    /** 模板L2结算单价，单位为分 */
    @Schema(description = "模板L2结算单价，单位为分", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "9800")
    private Long buyerSettlementUnitPrice;

    /** 面值币种，一期固定CNY */
    @Schema(description = "面值币种，一期固定CNY", requiredMode = Schema.RequiredMode.REQUIRED, example = "CNY")
    private String currency;

    /** 采购商商品生命周期：0草稿，10已发布，20暂停，90已退役 */
    @Schema(description = "采购商商品生命周期：0草稿，10已发布，20暂停，90已退役", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "PUBLISHED")
    private String listingStatus;

    /** 供给可用性：10可用，20不可用，30未知 */
    @Schema(description = "供给可用性：10可用，20不可用，30未知", requiredMode = Schema.RequiredMode.REQUIRED, example = "AVAILABLE")
    private String availabilityStatus;

    /** 相关可用性投影版本号 */
    @Schema(description = "相关可用性投影版本号", requiredMode = Schema.RequiredMode.REQUIRED, example = "7")
    private Long availabilityVersion;

    /** 投影最后更新时间，Asia/Shanghai */
    @Schema(description = "投影最后更新时间，Asia/Shanghai", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-08-11T02:30:00Z")
    private OffsetDateTime updatedAt;
}
