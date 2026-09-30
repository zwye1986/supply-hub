package com.aifuli.module.supplychain.tradecore.account.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.account.dal.dataobject.BuyerAccountDO;
import com.aifuli.module.supplychain.tradecore.account.domain.Money;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface BuyerAccountMapper extends BaseMapperX<BuyerAccountDO> {

    default BuyerAccountDO selectByBuyerId(Long buyerId) {
        return selectOne(BuyerAccountDO::getBuyerId, buyerId,
                BuyerAccountDO::getCurrency, Money.CURRENCY_CNY);
    }

    default BuyerAccountDO selectByBuyerIdForUpdate(Long buyerId) {
        return selectOneForUpdate(BuyerAccountDO::getBuyerId, buyerId,
                BuyerAccountDO::getCurrency, Money.CURRENCY_CNY);
    }

    default BuyerAccountDO selectByIdForUpdate(Long id) {
        return selectOneForUpdate(BuyerAccountDO::getId, id);
    }

    /**
     * 原子冻结金额。一期不检查可用余额，允许 {@code frozen > balance}。
     *
     * <p><b>为何保留 {@code @Update}（开发规范第 8 条例外）</b>：
     * WHERE 使用版本 CAS，并在同一语句内执行
     * {@code frozen = frozen + amount}、{@code version = version + 1}。
     * LambdaUpdateWrapper 虽可用 {@code apply}/{@code setSql} 拼装，但拼写漂移会直接导致冻结不平衡，
     * 故本路径保持显式注解 SQL。一期不变量见
     * {@code docs/superpowers/specs/2026-08-17-supply-chain-phase1-buyer-account-overdraft.md}。</p>
     */
    @Update("UPDATE sc_buyer_account SET frozen = frozen + #{amount}, version = version + 1, "
            + "updated_at = #{now} WHERE id = #{accountId} AND status = 10 "
            + "AND version = #{expectedVersion}")
    int atomicFreeze(@Param("accountId") Long accountId, @Param("expectedVersion") long expectedVersion,
                     @Param("amount") long amount, @Param("now") LocalDateTime now);

    /**
     * 原子确认扣款（解冻并扣减余额）。一期允许 {@code balance} 变为负数。
     *
     * <p><b>为何保留 {@code @Update}（开发规范第 8 条例外）</b>：
     * WHERE/SET 含 {@code frozen >= amount} 及
     * {@code balance = balance - amount}、{@code frozen = frozen - amount} 列算术 CAS。
     * 误改会导致超额解冻或冻结不平衡，故保持显式注解 SQL。</p>
     */
    @Update("UPDATE sc_buyer_account SET balance = balance - #{amount}, frozen = frozen - #{amount}, "
            + "version = version + 1, updated_at = #{now} WHERE id = #{accountId} AND status = 10 "
            + "AND version = #{expectedVersion} AND frozen >= #{amount}")
    int atomicCommit(@Param("accountId") Long accountId, @Param("expectedVersion") long expectedVersion,
                     @Param("amount") long amount, @Param("now") LocalDateTime now);

    /**
     * 原子释放冻结金额。
     *
     * <p><b>为何保留 {@code @Update}（开发规范第 8 条例外）</b>：
     * WHERE/SET 含 {@code frozen >= amount} 与 {@code frozen = frozen - amount} 列算术 CAS。
     * 误改会导致超额解冻，故保持显式注解 SQL。</p>
     */
    @Update("UPDATE sc_buyer_account SET frozen = frozen - #{amount}, version = version + 1, "
            + "updated_at = #{now} WHERE id = #{accountId} AND version = #{expectedVersion} "
            + "AND frozen >= #{amount}")
    int atomicRelease(@Param("accountId") Long accountId, @Param("expectedVersion") long expectedVersion,
                      @Param("amount") long amount, @Param("now") LocalDateTime now);

    /**
     * 原子返还已扣资金：balance 增加 amount，frozen 不变。REFUND 账本语义要求
     * balance_delta &gt; 0 且 frozen_delta = 0（sc_account_ledger CHECK 约束）。
     *
     * <p><b>为何保留 {@code @Update}（开发规范第 8 条例外）</b>：与
     * {@link #atomicFreeze} 同构——WHERE 使用版本 CAS 并要求账户处于激活态，
     * 同一语句内执行 {@code balance = balance + amount}；拼写漂移会直接导致
     * 账本与账户余额不一致，故保持显式注解 SQL。
     * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#45-生产事务与幂等不变量} 第 6 条。</p>
     */
    @Update("UPDATE sc_buyer_account SET balance = balance + #{amount}, version = version + 1, "
            + "updated_at = #{now} WHERE id = #{accountId} AND status = 10 "
            + "AND version = #{expectedVersion}")
    int atomicReturn(@Param("accountId") Long accountId, @Param("expectedVersion") long expectedVersion,
                     @Param("amount") long amount, @Param("now") LocalDateTime now);
}
