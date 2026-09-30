package com.aifuli.module.supplychain.tradecore.supply.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@TableName("sc_supplier_contact")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierContactDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long supplierId;
    private Integer sortNo;
    private String contactName;
    private String contactMobile;
    private String contactEmail;
    @Version
    private Long version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
