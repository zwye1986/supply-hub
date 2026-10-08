package com.aifuli.module.supplychain.infrastructure.token.huasheng;

import com.aifuli.module.supplychain.border.fulfillment.application.huasheng.HuashengTokenCachePort;
import com.aifuli.module.supplychain.border.fulfillment.application.huasheng.HuashengTokenEntry;
import com.aifuli.module.supplychain.border.fulfillment.application.huasheng.HuashengTokenIdentity;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 华昇基础设施层内存 Token 缓存实现。
 *
 * <p>职责：
 * 实现应用层 {@link HuashengTokenCachePort} 端口，提供基于 Identity 的单飞锁并发控制、
 * 基于 generation 的 CAS 安全条件失效、1024 键容量上限守卫与闲置锁安全退休机制。
 *
 * <p>不变量：
 * <ul>
 *   <li>容量上限默认为 1024 键，超限时优先淘汰已过期条目；若仍满额则安全失败，绝不驱逐持有活跃锁的槽位；</li>
 *   <li>并发获取相同 Identity 的 Token 时，通过 {@link #withIdentityLease(HuashengTokenIdentity, Supplier)} 严格串行化；</li>
 *   <li>{@link #invalidateIfGeneration(HuashengTokenIdentity, long)} 仅在 generation 匹配时移除条目；</li>
 *   <li>槽位空闲且无在途锁时自动安全退休，防止历史键无限驻留导致内存泄漏；</li>
 *   <li>零反向依赖：绝不依赖应用层具体业务实现或适配器层类。</li>
 * </ul>
 *
 * <p>关键决策：
 * 单飞锁采用基于键的细粒度 {@link ReentrantLock} 配合引用计数；双时钟过期检查保证时钟回拨下不复用过期条目。
 *
 * <p>设计路径与章节锚点：
 * docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#183-内存缓存实现与单飞锁ad-a5-05、§8.1。
 */
public class HuashengInMemoryTokenCache implements HuashengTokenCachePort {

    /** 默认最大缓存身份容量（1024 键）。 */
    public static final int DEFAULT_MAX_CAPACITY = 1024;

    private static final class CacheSlot {
        final ReentrantLock lock = new ReentrantLock();
        final AtomicInteger refCount = new AtomicInteger(0);
        volatile HuashengTokenEntry entry;
    }

    private final ConcurrentHashMap<HuashengTokenIdentity, CacheSlot> slots = new ConcurrentHashMap<>();
    private final Clock clock;
    private final LongSupplier nanoTicker;
    private final int maxCapacity;

    /**
     * 构造内存 Token 缓存（使用默认系统时钟与 1024 默认容量）。
     */
    public HuashengInMemoryTokenCache() {
        this(Clock.systemUTC(), System::nanoTime, DEFAULT_MAX_CAPACITY);
    }

    /**
     * 构造内存 Token 缓存（可注入时钟源与容量上限，便于契约测试）。
     *
     * @param clock Wall Clock 时钟，非空
     * @param nanoTicker 单调 Ticker 时钟源，非空
     * @param maxCapacity 最大允许身份键数，大于 0
     */
    public HuashengInMemoryTokenCache(Clock clock, LongSupplier nanoTicker, int maxCapacity) {
        this.clock = Objects.requireNonNull(clock, "clock不能为null");
        this.nanoTicker = Objects.requireNonNull(nanoTicker, "nanoTicker不能为null");
        if (maxCapacity <= 0) {
            throw new IllegalArgumentException("maxCapacity必须为正整数: " + maxCapacity);
        }
        this.maxCapacity = maxCapacity;
    }

    @Override
    public Optional<HuashengTokenEntry> get(HuashengTokenIdentity identity) {
        Objects.requireNonNull(identity, "identity不能为null");
        CacheSlot slot = slots.get(identity);
        if (slot == null) {
            return Optional.empty();
        }
        HuashengTokenEntry entry = slot.entry;
        return Optional.ofNullable(entry);
    }

    @Override
    public void put(HuashengTokenIdentity identity, HuashengTokenEntry entry) {
        Objects.requireNonNull(identity, "identity不能为null");
        Objects.requireNonNull(entry, "entry不能为null");

        CacheSlot slot = slots.get(identity);
        if (slot == null) {
            evictExpiredIfNeeded();
            if (!slots.containsKey(identity) && slots.size() >= maxCapacity) {
                throw new IllegalStateException("Token cache 容量超限: " + maxCapacity);
            }
            slot = slots.computeIfAbsent(identity, k -> new CacheSlot());
        }
        slot.entry = entry;
    }

    @Override
    public boolean invalidateIfGeneration(HuashengTokenIdentity identity, long generation) {
        Objects.requireNonNull(identity, "identity不能为null");
        CacheSlot slot = slots.get(identity);
        if (slot == null) {
            return false;
        }

        slot.lock.lock();
        try {
            HuashengTokenEntry current = slot.entry;
            if (current != null && current.generation() == generation) {
                slot.entry = null;
                tryRetireSlot(identity, slot);
                return true;
            }
            return false;
        } finally {
            slot.lock.unlock();
        }
    }

    @Override
    public <R> R withIdentityLease(HuashengTokenIdentity identity, Supplier<R> action) {
        Objects.requireNonNull(identity, "identity不能为null");
        Objects.requireNonNull(action, "action不能为null");

        CacheSlot slot = acquireSlot(identity);
        slot.lock.lock();
        try {
            return action.get();
        } finally {
            slot.lock.unlock();
            releaseSlot(identity, slot);
        }
    }

    private CacheSlot acquireSlot(HuashengTokenIdentity identity) {
        while (true) {
            CacheSlot slot = slots.get(identity);
            if (slot == null) {
                evictExpiredIfNeeded();
                if (!slots.containsKey(identity) && slots.size() >= maxCapacity) {
                    throw new IllegalStateException("Token cache 容量超限: " + maxCapacity);
                }
                slot = slots.computeIfAbsent(identity, k -> new CacheSlot());
            }
            slot.refCount.incrementAndGet();
            if (slots.get(identity) == slot) {
                return slot;
            }
            slot.refCount.decrementAndGet();
        }
    }

    private void releaseSlot(HuashengTokenIdentity identity, CacheSlot slot) {
        slot.refCount.decrementAndGet();
        tryRetireSlot(identity, slot);
    }

    private void tryRetireSlot(HuashengTokenIdentity identity, CacheSlot slot) {
        if (slot.refCount.get() <= 0 && slot.entry == null && !slot.lock.isLocked()) {
            slots.remove(identity, slot);
        }
    }

    private void evictExpiredIfNeeded() {
        if (slots.size() < maxCapacity) {
            return;
        }
        for (Map.Entry<HuashengTokenIdentity, CacheSlot> e : slots.entrySet()) {
            CacheSlot s = e.getValue();
            HuashengTokenEntry ent = s.entry;
            if (ent != null && ent.isExpired(clock, nanoTicker) && s.refCount.get() == 0 && !s.lock.isLocked()) {
                s.entry = null;
                slots.remove(e.getKey(), s);
            } else if (ent == null && s.refCount.get() == 0 && !s.lock.isLocked()) {
                slots.remove(e.getKey(), s);
            }
        }
    }
}
