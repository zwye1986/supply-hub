package com.aifuli.module.supplychain.border.fulfillment.application.huasheng;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 华昇 Token 内存缓存纯应用端口契约。
 *
 * <p>职责：
 * 定义 Token 缓存条目的存取、基于 generation 的 CAS 条件失效与身份级单飞租约加锁接口，
 * 供应用层 {@link HuashengTokenManager} 消费，与底层具体缓存实现彻底解耦。
 *
 * <p>不变量：
 * <ul>
 *   <li>纯接口契约，应用层绝不反向依赖 infrastructure 实现；</li>
 *   <li>所有方法入参身份与条目均要求非空；</li>
 *   <li>{@link #withIdentityLease(HuashengTokenIdentity, Supplier)} 必须保证同一 Identity 在同一进程内串行执行；</li>
 *   <li>{@link #invalidateIfGeneration(HuashengTokenIdentity, long)} 仅在代次一致时生效。</li>
 * </ul>
 *
 * <p>关键决策：
 * 六边形架构端口，应用层对缓存具体技术栈无感；由外部测试或未来集成装配点显式注入。
 *
 * <p>设计路径与章节锚点：
 * docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#181-架构分层解耦与六边形依赖ad-4--ad-a5-01、§8.1。
 */
public interface HuashengTokenCachePort {

    /**
     * 根据复合身份获取缓存条目。
     *
     * @param identity 复合身份主键，非空
     * @return 包含 {@link HuashengTokenEntry} 的 Optional，若未命中则返回 empty
     */
    Optional<HuashengTokenEntry> get(HuashengTokenIdentity identity);

    /**
     * 将 Token 条目存入缓存。
     *
     * @param identity 复合身份主键，非空
     * @param entry Token 缓存条目，非空
     */
    void put(HuashengTokenIdentity identity, HuashengTokenEntry entry);

    /**
     * 基于 CAS 代次（generation）条件失效缓存条目。
     *
     * @param identity 复合身份主键，非空
     * @param generation 尝试失效的代次编号
     * @return 若当前条目存在且代次匹配成功移除则返回 true，否则返回 false
     */
    boolean invalidateIfGeneration(HuashengTokenIdentity identity, long generation);

    /**
     * 获取指定复合身份的单飞互斥租约并执行业务动作。
     *
     * @param identity 复合身份主键，非空
     * @param action 租约持有期间执行的回调逻辑，非空
     * @param <R> 返回值泛型
     * @return 回调逻辑的执行结果
     */
    <R> R withIdentityLease(HuashengTokenIdentity identity, Supplier<R> action);
}
