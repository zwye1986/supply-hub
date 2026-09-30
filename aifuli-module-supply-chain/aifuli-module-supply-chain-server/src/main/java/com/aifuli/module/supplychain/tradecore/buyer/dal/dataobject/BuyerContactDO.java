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

/**
 * 分销商运营联系人。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#13-模块边界}。
 */
@TableName("sc_buyer_contact")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuyerContactDO {

    /** 分销商联系人主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 采购商内部主键，逻辑引用sc_buyer.id */
    private Long buyerId;
    /** 排序，0为主联系人 */
    private Integer sortNo;
    /** 联系人姓名 */
    private String contactName;
    /** 联系人手机号 */
    private String contactMobile;
    /** 联系人邮箱 */
    private String contactEmail;
    /** 乐观锁版本号 */
    @Version
    private Long version;
    /** 创建人用户ID */
    private Long createdBy;
    /** 创建时间，Asia/Shanghai */
    private LocalDateTime createdAt;
    /** 最后修改人用户ID */
    private Long updatedBy;
    /** 最后修改时间，Asia/Shanghai */
    private LocalDateTime updatedAt;
}
