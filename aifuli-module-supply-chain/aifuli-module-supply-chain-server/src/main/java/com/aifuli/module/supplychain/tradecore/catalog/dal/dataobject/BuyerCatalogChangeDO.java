package com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@TableName("sc_buyer_catalog_change")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuyerCatalogChangeDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long buyerId;
    private String changeType;
    private Long listingId;
    private Long listingVersionId;
    private Long availabilityVersion;
    private LocalDateTime occurredAt;
    private byte[] payloadDigest;
}
