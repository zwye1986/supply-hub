package com.aifuli.module.supplychain.tradecore.supply.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 供应商凭据集（AD-3，变更集 054）：一个供应商可持有多套密钥材料集合，按 set_code 供应商内唯一。
 *
 * <p>设计见 {@code docs/superpowers/specs/2026-10-02-huasheng-fulfillment-adapter-design.md#71-地基变更集-ddl-全文054-凭据集--055-extra_config}。</p>
 */
@TableName("sc_credential_set")
@Data
@Builder
public class CredentialSetDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属供应商内部主键，逻辑引用sc_supplier.id */
    private Long supplierId;
    /** 凭据集编号，供应商内唯一 */
    private String setCode;
    /** 凭据集状态：10可用，20停用，90归档 */
    private Short status;
    /** 创建时间，Asia/Shanghai */
    private LocalDateTime createdAt;
    /** 最后更新时间，Asia/Shanghai */
    private LocalDateTime updatedAt;
}
