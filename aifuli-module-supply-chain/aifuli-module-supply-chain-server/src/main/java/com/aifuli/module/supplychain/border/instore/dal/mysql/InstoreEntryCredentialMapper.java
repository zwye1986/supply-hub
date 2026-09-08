package com.aifuli.module.supplychain.border.instore.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreEntryCredentialDO;
import com.aifuli.module.supplychain.border.instore.domain.InstoreCredentialRevokeReason;
import com.aifuli.module.supplychain.border.instore.domain.InstoreEntryCredentialStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;

/**
 * 到店购入口凭证 Mapper；所有状态变更都同时校验当前状态、版本和有效期。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#7-身份与入口}。</p>
 */
@Mapper
public interface InstoreEntryCredentialMapper extends BaseMapperX<InstoreEntryCredentialDO> {

    /** 按明文 token 的 SHA-256 摘要查询，禁止接收或查询明文 token 列。 */
    default InstoreEntryCredentialDO selectByTokenDigest(byte[] digest) {
        return selectOne(new LambdaQueryWrapper<InstoreEntryCredentialDO>()
                .eq(InstoreEntryCredentialDO::getTokenDigest, digest));
    }

    /** ACTIVE 且未过期时 CAS 抢占为 PROCESSING。 */
    default int claimProcessing(Long id, long expectedVersion, LocalDateTime now) {
        return update(null, new LambdaUpdateWrapper<InstoreEntryCredentialDO>()
                .set(InstoreEntryCredentialDO::getStatus, InstoreEntryCredentialStatus.PROCESSING.getCode())
                .set(InstoreEntryCredentialDO::getProcessingStartedAt, now)
                .set(InstoreEntryCredentialDO::getUpdatedAt, now)
                .setSql("version = version + 1")
                .eq(InstoreEntryCredentialDO::getId, id)
                .eq(InstoreEntryCredentialDO::getVersion, expectedVersion)
                .eq(InstoreEntryCredentialDO::getStatus, InstoreEntryCredentialStatus.ACTIVE.getCode())
                .gt(InstoreEntryCredentialDO::getExpiresAt, now));
    }

    /** PROCESSING 状态在会员资料成功返回后 CAS 标记为 USED。 */
    default int markUsed(Long id, long expectedVersion, LocalDateTime now) {
        return update(null, new LambdaUpdateWrapper<InstoreEntryCredentialDO>()
                .set(InstoreEntryCredentialDO::getStatus, InstoreEntryCredentialStatus.USED.getCode())
                .set(InstoreEntryCredentialDO::getUsedAt, now)
                .set(InstoreEntryCredentialDO::getUpdatedAt, now)
                .setSql("version = version + 1")
                .eq(InstoreEntryCredentialDO::getId, id)
                .eq(InstoreEntryCredentialDO::getVersion, expectedVersion)
                .eq(InstoreEntryCredentialDO::getStatus, InstoreEntryCredentialStatus.PROCESSING.getCode()));
    }

    /** 明确的会员查询失败后，仅将未过期 PROCESSING 凭证释放回 ACTIVE。 */
    default int releaseProcessing(Long id, long expectedVersion, LocalDateTime now) {
        return update(null, new LambdaUpdateWrapper<InstoreEntryCredentialDO>()
                .set(InstoreEntryCredentialDO::getStatus, InstoreEntryCredentialStatus.ACTIVE.getCode())
                .set(InstoreEntryCredentialDO::getProcessingStartedAt, null)
                .set(InstoreEntryCredentialDO::getUpdatedAt, now)
                .setSql("version = version + 1")
                .eq(InstoreEntryCredentialDO::getId, id)
                .eq(InstoreEntryCredentialDO::getVersion, expectedVersion)
                .eq(InstoreEntryCredentialDO::getStatus, InstoreEntryCredentialStatus.PROCESSING.getCode())
                .gt(InstoreEntryCredentialDO::getExpiresAt, now));
    }

    /** 仅将已到期的 ACTIVE/PROCESSING 凭证 CAS 标记为 EXPIRED。 */
    default int markExpired(Long id, long expectedVersion, LocalDateTime now) {
        return update(null, new LambdaUpdateWrapper<InstoreEntryCredentialDO>()
                .set(InstoreEntryCredentialDO::getStatus, InstoreEntryCredentialStatus.EXPIRED.getCode())
                .set(InstoreEntryCredentialDO::getProcessingStartedAt, null)
                .set(InstoreEntryCredentialDO::getUpdatedAt, now)
                .setSql("version = version + 1")
                .eq(InstoreEntryCredentialDO::getId, id)
                .eq(InstoreEntryCredentialDO::getVersion, expectedVersion)
                .in(InstoreEntryCredentialDO::getStatus,
                        InstoreEntryCredentialStatus.ACTIVE.getCode(),
                        InstoreEntryCredentialStatus.PROCESSING.getCode())
                .le(InstoreEntryCredentialDO::getExpiresAt, now));
    }

    /**
     * 入口 URL 调用结果未知或冻结档案退役时，将 ACTIVE/PROCESSING 凭证 CAS 撤销为 REVOKED 终态。
     *
     * <p>撤销同时写入撤销时间与稳定撤销原因，满足
     * {@code ck_sc_instore_credential_revoke} 一致性 CHECK。设计见
     * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#8-凭证状态机}。</p>
     */
    default int revoke(Long id, long expectedVersion,
                       InstoreCredentialRevokeReason reason, LocalDateTime now) {
        return update(null, new LambdaUpdateWrapper<InstoreEntryCredentialDO>()
                .set(InstoreEntryCredentialDO::getStatus, InstoreEntryCredentialStatus.REVOKED.getCode())
                .set(InstoreEntryCredentialDO::getProcessingStartedAt, null)
                .set(InstoreEntryCredentialDO::getRevokedAt, now)
                .set(InstoreEntryCredentialDO::getRevokeReason, reason.name())
                .set(InstoreEntryCredentialDO::getUpdatedAt, now)
                .setSql("version = version + 1")
                .eq(InstoreEntryCredentialDO::getId, id)
                .eq(InstoreEntryCredentialDO::getVersion, expectedVersion)
                .in(InstoreEntryCredentialDO::getStatus,
                        InstoreEntryCredentialStatus.ACTIVE.getCode(),
                        InstoreEntryCredentialStatus.PROCESSING.getCode()));
    }
}
