package com.aifuli.module.supplychain.border.fulfillment.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 持久化存量覆盖门禁，禁止默认放行；见
 * docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#101-迁移执行与核实边界。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sc_recharge_frequency_migration_gate")
public class RechargeFrequencyMigrationGateDO {
    /** 迁移发布门禁固定主键。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 迁移覆盖状态：CLOSED未核验，READY已核验。 */
    private String state;

    /** 完成迁移覆盖核验的不可变部署版本。 */
    private String verifiedArtifact;

    /** 迁移覆盖核验的专用手机号摘要密钥指纹；不含密钥原文。 */
    private byte[] identityKeyFingerprint;

    /** 完成迁移覆盖核验时间，Asia/Shanghai。 */
    private LocalDateTime verifiedAt;

    /** 乐观锁版本。 */
    @Version private Long version;
}
