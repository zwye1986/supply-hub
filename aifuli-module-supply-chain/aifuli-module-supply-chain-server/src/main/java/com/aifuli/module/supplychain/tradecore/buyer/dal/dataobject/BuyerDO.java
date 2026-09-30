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
 * 供应链采购商，承载分销商档案与组织树字段。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#13-模块边界}。
 */
@TableName("sc_buyer")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuyerDO {

    /** 采购商内部主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 采购商永久业务编码 */
    private String buyerCode;
    /** 分销商内部全局短序号，供项目编码父段引用 */
    private Short codeScopeNo;
    /** 采购商标准名称 */
    private String buyerName;
    /** 采购商列表简称 */
    private String shortName;
    /** 采购商类型，一期为SAAS_MALL */
    private String buyerType;
    /** 管理状态：10启用，20暂停，90已归档 */
    private Short status;
    /** 上级分销商主键，逻辑引用sc_buyer.id，一级为空 */
    private Long parentBuyerId;
    /** 组织等级：1一级 2二级 3三级 */
    private Short distributionLevel;
    /** 分销商类型：ENTERPRISE企业 PERSONAL个人 PLATFORM平台 */
    private String distributorType;
    /** 供货择优倾向，不改变路由引擎 */
    private String supplyPreference;
    /** 结算方式封闭枚举 */
    private String settlementMethod;
    /** 结算周期天数 */
    private Integer settlementCycleDays;
    /** 合作开始时间，Asia/Shanghai */
    private LocalDateTime cooperationStartedAt;
    /** 合作说明 */
    private String cooperationNote;
    /** 公司名称 */
    private String legalEntityName;
    /** 统一社会信用代码 */
    private String creditCode;
    /** 法人姓名 */
    private String legalPersonName;
    /** 法人手机号密文 */
    private byte[] legalPersonMobileCipher;
    /** 联系地址 */
    private String contactAddress;
    /** 开户行 */
    private String bankName;
    /** 开户账号密文 */
    private byte[] bankAccountCipher;
    /** 开户账号信封密钥版本 */
    private String bankAccountKeyVersion;
    /** 证件信封密钥版本 */
    private String documentKeyVersion;
    /** 法人身份证正面fileKey密文 */
    private byte[] idFrontCipher;
    /** 法人身份证反面fileKey密文 */
    private byte[] idBackCipher;
    /** 运营联系人姓名 */
    private String contactName;
    /** 运营联系人手机号 */
    private String contactMobile;
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
