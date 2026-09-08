package com.aifuli.module.supplychain.tradecore.supply.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("sc_credential_slot")
@Data
@Builder
public class CredentialSlotDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String slotNo;
    private String providerType;
    private String secretRef;
    private String maskedHint;
    private String activeSecretVersion;
    private Short status;
    @Version
    private Long version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
