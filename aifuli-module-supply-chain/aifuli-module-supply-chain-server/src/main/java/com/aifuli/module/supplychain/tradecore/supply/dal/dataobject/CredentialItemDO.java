package com.aifuli.module.supplychain.tradecore.supply.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 凭据集内的单个密钥项引用（AD-3，变更集 054）：禁止存明文。
 *
 * <p>{@code providerType=ENV} 时 {@code secretRef} 存 {@code env:NAME} 引用（密钥不入库）；
 * {@code providerType=ENVELOPE} 时 {@code secretRef} 存 {@code EnvelopeCryptoPort} 信封密文
 * （格式 {@code envelope:<keyVersion>:<base64(iv||ciphertext)>}，AAD 绑定 {@code credentialSetId|itemKey}）。
 * 任何读取只返回 {@code maskedHint}，永不回显 {@code secretRef}。设计见
 * {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#52-ad-3-凭据集变更集-054-envelope-provider}。</p>
 */
@TableName("sc_credential_item")
@Data
@Builder(toBuilder = true)
public class CredentialItemDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 凭据集主键，逻辑引用sc_credential_set.id */
    private Long credentialSetId;
    /** 凭据项键，如appSecret、privateKey、callbackPublicKey、secretKey */
    private String itemKey;
    /** 密钥托管类型：ENV环境变量引用，ENVELOPE信封加密密文，KMS托管 */
    private String providerType;
    /** 密钥系统中的秘密引用或信封密文，不是秘密明文；ENV存env:NAME */
    private String secretRef;
    /** 供后台识别的脱敏提示，禁止含可还原信息 */
    private String maskedHint;
    /** 当前生效密钥版本 */
    private Integer activeSecretVersion;
    /** 凭据项状态：10可用，20停用，90归档 */
    private Short status;
    /** 创建时间，Asia/Shanghai */
    private LocalDateTime createdAt;
    /** 最后更新时间，Asia/Shanghai */
    private LocalDateTime updatedAt;
}
