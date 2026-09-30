package com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@TableName("sc_buyer_tenant_binding")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuyerTenantBindingDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long buyerId;
    private String sourceSystem;
    private String externalTenantId;
    private Integer bindingVersion;
    private LocalDateTime validFrom;
    private LocalDateTime validTo;
    private Short currentFlag;
    private Short status;
    @Version
    private Long version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
