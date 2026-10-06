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
    /** 供给评估来源身份版本及路由资格的SHA256摘要 */
    private byte[] basisDigest;
    /** 供给评估来源引用及资格摘要，不含凭据、履约目标或完整供应商报文 */
    private String basisJson;
    /** 供给可用性投影下次必须重建的时间，Asia/Shanghai；历史未评估为空 */
    private LocalDateTime validUntil;
    @Version
    private Long version;
    private LocalDateTime updatedAt;
}
