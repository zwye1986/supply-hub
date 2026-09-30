package com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 分销商执照与资质文件引用。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#13-模块边界}。
 */
@TableName("sc_buyer_file_ref")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuyerFileRefDO {

    /** LICENSE执照 */
    public static final String FILE_ROLE_LICENSE = "LICENSE";
    /** QUALIFICATION资质 */
    public static final String FILE_ROLE_QUALIFICATION = "QUALIFICATION";

    /** 分销商证件文件行主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 采购商内部主键，逻辑引用sc_buyer.id */
    private Long buyerId;
    /** LICENSE执照 QUALIFICATION资质 */
    private String fileRole;
    /** 同一角色内排序 */
    private Integer sortNo;
    /** 文件fileKey密文 */
    private byte[] fileCipher;
    /** 信封密钥版本 */
    private String keyVersion;
    /** 创建时间，Asia/Shanghai */
    private LocalDateTime createdAt;
}
