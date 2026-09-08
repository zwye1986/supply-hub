package com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@TableName("sc_buyer_listing_version")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuyerListingVersionDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long listingId;
    private Integer versionNo;
    private Long buyerSettlementUnitPrice;
    private String currency;
    private String displayName;
    private Long minQuantity;
    private Long maxQuantity;
    private LocalDateTime validFrom;
    private LocalDateTime validTo;
    private String publishReason;
    private Long publishedBy;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
}
