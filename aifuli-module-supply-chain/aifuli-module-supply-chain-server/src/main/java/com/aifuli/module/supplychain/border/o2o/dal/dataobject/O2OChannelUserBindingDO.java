package com.aifuli.module.supplychain.border.o2o.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * O2O 渠道用户绑定 DO，对应 {@code sc_o2o_channel_user_binding}。
 *
 * <p>以渠道匿名用户标识安全映射采购商及其 C 端用户，禁止渠道直接声明内部归属。
 * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#9.1-O2OChannelUserBinding}。</p>
 */
@TableName("sc_o2o_channel_user_binding")
@Data
@Builder
public class O2OChannelUserBindingDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String bindingNo;
    private Long buyerId;
    private String buyerUserId;
    private String channelCode;
    private String channelUserId;
    private Short status;
    @Version
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
