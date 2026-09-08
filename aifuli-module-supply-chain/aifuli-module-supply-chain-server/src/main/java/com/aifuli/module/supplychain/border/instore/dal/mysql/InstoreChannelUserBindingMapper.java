package com.aifuli.module.supplychain.border.instore.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreChannelUserBindingDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;

/**
 * 到店购渠道用户绑定 Mapper。
 *
 * <p>遵循规范：优先 LambdaQuery/UpdateWrapper，不新增注解 SQL。
 * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#7-身份绑定-token-反向模型}。</p>
 */
@Mapper
public interface InstoreChannelUserBindingMapper extends BaseMapperX<InstoreChannelUserBindingDO> {

    /**
     * 按项目会员业务身份查询稳定绑定。
     *
     * <p>{@code buyerUserId} 是不透明字符串；即使两个项目路由到同一 externalTenantId，
     * 绑定事实也不得跨 projectId 合并。设计见
     * {@code docs/superpowers/specs/2026-08-21-supply-chain-buyer-project-design.md#9-订单用户与幂等}。</p>
     */
    default InstoreChannelUserBindingDO selectByProjectUser(
            Long projectId, String buyerUserId, String channelCode) {
        return selectOne(new LambdaQueryWrapper<InstoreChannelUserBindingDO>()
                .eq(InstoreChannelUserBindingDO::getProjectId, projectId)
                .eq(InstoreChannelUserBindingDO::getBuyerUserId, buyerUserId)
                .eq(InstoreChannelUserBindingDO::getChannelCode, channelCode));
    }

    /**
     * 以版本 CAS 刷新绑定上的最近路由快照。
     *
     * <p>{@code sourceSystem/externalTenantId} 表示最近一次签发观察到的路由，不是唯一键，
     * 也不控制旧凭证；影响行数不是 1 表示并发推进，由调用方失败。设计见
     * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#6-稳定绑定}。</p>
     */
    default int updateMemberRouteSnapshot(Long id, long expectedVersion,
                                          String sourceSystem, String externalTenantId,
                                          LocalDateTime now) {
        return update(null, new LambdaUpdateWrapper<InstoreChannelUserBindingDO>()
                .set(InstoreChannelUserBindingDO::getSourceSystem, sourceSystem)
                .set(InstoreChannelUserBindingDO::getExternalTenantId, externalTenantId)
                .set(InstoreChannelUserBindingDO::getUpdatedAt, now)
                .setSql("version = version + 1")
                .eq(InstoreChannelUserBindingDO::getId, id)
                .eq(InstoreChannelUserBindingDO::getVersion, expectedVersion));
    }

    /**
     * @deprecated 014 过渡态租户四元组不能区分共享 SaaS 租户的多个采购项目；
     * 新业务使用 {@link #selectByProjectUser(Long, String, String)}。
     *
     * <p>{@code buyerUserId} 必须作为不透明字符串使用，不得转换为 Long。详见
     * {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#7-身份与入口}。</p>
     */
    @Deprecated
    default InstoreChannelUserBindingDO selectByTenantUser(String sourceSystem, String externalTenantId,
                                                            String buyerUserId, String channelCode) {
        return selectOne(new LambdaQueryWrapper<InstoreChannelUserBindingDO>()
                .eq(InstoreChannelUserBindingDO::getSourceSystem, sourceSystem)
                .eq(InstoreChannelUserBindingDO::getExternalTenantId, externalTenantId)
                .eq(InstoreChannelUserBindingDO::getBuyerUserId, buyerUserId)
                .eq(InstoreChannelUserBindingDO::getChannelCode, channelCode));
    }

    /** @deprecated 仅供 013 历史兼容；生产入口凭证必须通过独立凭证表解析。 */
    @Deprecated
    default InstoreChannelUserBindingDO selectActiveByToken(String channelCode, String token) {
        return selectOne(new LambdaQueryWrapper<InstoreChannelUserBindingDO>()
                .eq(InstoreChannelUserBindingDO::getChannelCode, channelCode)
                .eq(InstoreChannelUserBindingDO::getToken, token)
                .eq(InstoreChannelUserBindingDO::getTokenStatus, (short) 10));
    }

    /** @deprecated 仅供 013 历史兼容；生产身份必须按租户四元组解析。 */
    @Deprecated
    default InstoreChannelUserBindingDO selectActiveByChannelUser(String channelCode, String channelUserId) {
        return selectOne(new LambdaQueryWrapper<InstoreChannelUserBindingDO>()
                .eq(InstoreChannelUserBindingDO::getChannelCode, channelCode)
                .eq(InstoreChannelUserBindingDO::getChannelUserId, channelUserId)
                .eq(InstoreChannelUserBindingDO::getStatus, (short) 10));
    }

    /** @deprecated 旧唯一键不能区分 SaaS 租户，仅供 013 历史兼容。 */
    @Deprecated
    default InstoreChannelUserBindingDO selectByBuyerUser(Long buyerId, String buyerUserId, String channelCode) {
        return selectOne(new LambdaQueryWrapper<InstoreChannelUserBindingDO>()
                .eq(InstoreChannelUserBindingDO::getBuyerId, buyerId)
                .eq(InstoreChannelUserBindingDO::getBuyerUserId, buyerUserId)
                .eq(InstoreChannelUserBindingDO::getChannelCode, channelCode));
    }

    /** @deprecated 稳定绑定不再承载 token 状态；仅供 013 历史兼容。 */
    @Deprecated
    default int markTokenUsed(Long id, long expectedVersion, LocalDateTime updatedAt) {
        return update(null, new LambdaUpdateWrapper<InstoreChannelUserBindingDO>()
                .set(InstoreChannelUserBindingDO::getTokenStatus, (short) 20)
                .set(InstoreChannelUserBindingDO::getUpdatedAt, updatedAt)
                .setSql("version = version + 1")
                .eq(InstoreChannelUserBindingDO::getId, id)
                .eq(InstoreChannelUserBindingDO::getVersion, expectedVersion)
                .eq(InstoreChannelUserBindingDO::getTokenStatus, (short) 10));
    }
}
