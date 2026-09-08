package com.aifuli.module.supplychain.border.instore.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 到店购渠道用户绑定 DO，对应 {@code sc_instore_channel_user_binding}。
 *
 * <p>稳定身份键为 {@code (projectId, buyerUserId, channelCode)}；
 * {@code sourceSystem/externalTenantId} 只保留为已发布会员档案的路由快照，
 * {@code buyerUserId} 是 SaaS 商城会员的不透明字符串，禁止转换为 Long。旧的匿名会员号和 token
 * 字段仅用于 013 历史数据兼容，新入口凭证由独立凭证表管理。详见
 * {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#7-身份与入口}。</p>
 */
@TableName("sc_instore_channel_user_binding")
@Data
@Builder
public class InstoreChannelUserBindingDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String bindingNo;
    private Long buyerId;
    /** 采购项目内部主键，与 buyerUserId 共同确定会员业务身份。 */
    private Long projectId;
    /** 采购商来源系统编码，与 externalTenantId 共同确定 SaaS 租户。 */
    private String sourceSystem;
    /** 来源系统中的 SaaS 商城租户唯一标识。 */
    private String externalTenantId;
    /** SaaS 商城会员唯一标识，不透明字符串。 */
    private String buyerUserId;
    private String channelCode;
    /** 013 历史兼容列；014 起新增数据不再使用。 */
    private String channelUserId;
    /** 013 历史兼容列；014 起入口 token 不存入稳定绑定。 */
    private String token;
    /** 013 历史兼容列。 */
    private Short tokenStatus;
    /** 013 历史兼容列。 */
    private LocalDateTime tokenExpiresAt;
    private Short status;
    @Version
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
