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

@TableName("sc_buyer_listing_availability")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuyerListingAvailabilityDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long listingId;
    private Short availability;
    private String reasonCode;
    private LocalDateTime evaluatedAt;
    private Long basisVersion;
    @Version
    private Long version;
    private LocalDateTime updatedAt;
}
