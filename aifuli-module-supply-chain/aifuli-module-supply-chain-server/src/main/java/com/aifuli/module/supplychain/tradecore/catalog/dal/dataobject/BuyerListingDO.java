package com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@TableName("sc_buyer_listing")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuyerListingDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String listingNo;
    private Long buyerId;
    private Long platformSkuId;
    private String buyerSkuCode;
    /** 受理目录管理来源：MANUAL手工管理，AUTO_SELECTION页面选品自动管理 */
    @Builder.Default
    private String managementMode = com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingManagementMode.MANUAL.name();
    /** 自动受理目录关联的分销商品主键，逻辑引用sc_buyer_product.id；手工目录为空 */
    private Long buyerProductId;
    private Long currentVersionId;
    private Short status;
    @Version
    private Long version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
