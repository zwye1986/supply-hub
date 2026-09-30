package com.aifuli.module.supplychain.tradecore.supply.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 供应商渠道绑定 DO，对应 {@code sc_supplier_channel_binding}。
 *
 * <p>O2O 与 INSTORE 共用的渠道商户绑定：把 ele.me、大润发等渠道中的稳定商户标识
 * 映射为对应渠道的 Supplier，不保存门店。O2O 受理冻结该版本做履约路由，
 * INSTORE 受理与 HOME 入口校验用它确认 companyCode 已启用。
 * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#5-主数据与标准商品池}
 * 与 {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#9-业务流程}。</p>
 */
@TableName("sc_supplier_channel_binding")
@Data
@Builder
public class SupplierChannelBindingDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String channelCode;
    private String externalMerchantId;
    private Long supplierId;
    private Integer mappingVersion;
    private LocalDateTime validFrom;
    private LocalDateTime validTo;
    /** 当前映射标记：1 当前，NULL 历史。 */
    private Byte currentFlag;
    private Short status;
    @Version
    private Long version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
