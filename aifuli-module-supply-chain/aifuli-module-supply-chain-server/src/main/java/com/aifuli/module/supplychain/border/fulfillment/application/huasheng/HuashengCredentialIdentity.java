package com.aifuli.module.supplychain.border.fulfillment.application.huasheng;

import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalOnlySerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 华昇凭据身份纯值对象。
 *
 * <p>职责：
 * 封装凭据集 ID 与不可变排序凭据项快照集合，作为缓存复合身份键的核心维度之一。
 *
 * <p>不变量：
 * <ul>
 *   <li>credentialSetId 必须为大于 0 的正整数；</li>
 *   <li>sortedItems 必须非空且不可变，严格按 {@code itemKey} 升序，同 key 按 {@code itemId} 升序排列；</li>
 *   <li>类级标注 {@link JsonSerialize}(using = {@link HuashengInternalOnlySerializer}.class) 拒绝意外序列化；</li>
 *   <li>{@link #toString()} 严格脱敏，仅输出 credentialSetId 与项总数，绝不打印具体 key 或凭据项。</li>
 * </ul>
 *
 * <p>关键决策：
 * 作为应用层纯值模型，与具体存储模型完全解耦；不可变排序保证跨实例与跨调用的缓存身份键一致性。
 *
 * <p>设计路径与章节锚点：
 * docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#182-复合身份材料指纹与缓存条目ad-a5-02--ad-a5-04。
 */
@JsonSerialize(using = HuashengInternalOnlySerializer.class)
public final class HuashengCredentialIdentity {

    /**
     * 单个凭据项快照。
     *
     * @param itemId 凭据项主键 ID，大于 0
     * @param itemKey 凭据键名，非空非空白
     * @param providerType 提供方类型，非空
     * @param activeSecretVersion 当前生效密钥版本号，大于等于 0
     */
    public record CredentialItemSnapshot(
            long itemId,
            String itemKey,
            String providerType,
            int activeSecretVersion
    ) implements Comparable<CredentialItemSnapshot> {

        public CredentialItemSnapshot {
            if (itemId <= 0L) {
                throw new IllegalArgumentException("itemId必须为正整数");
            }
            if (itemKey == null || itemKey.isBlank()) {
                throw new IllegalArgumentException("itemKey不能为空或空白");
            }
            Objects.requireNonNull(providerType, "providerType不能为null");
            if (activeSecretVersion < 0) {
                throw new IllegalArgumentException("activeSecretVersion不能为负数");
            }
        }

        @Override
        public int compareTo(CredentialItemSnapshot other) {
            int cmp = this.itemKey.compareTo(other.itemKey);
            if (cmp != 0) {
                return cmp;
            }
            return Long.compare(this.itemId, other.itemId);
        }
    }

    private final long credentialSetId;
    private final List<CredentialItemSnapshot> sortedItems;

    /**
     * 构造凭据身份模型。
     *
     * @param credentialSetId 凭据集 ID，大于 0
     * @param items 凭据项快照列表，非空
     * @throws IllegalArgumentException 当 credentialSetId 非法或 items 为空时抛出
     */
    public HuashengCredentialIdentity(long credentialSetId, List<CredentialItemSnapshot> items) {
        if (credentialSetId <= 0L) {
            throw new IllegalArgumentException("credentialSetId必须为正整数");
        }
        Objects.requireNonNull(items, "items不能为null");

        List<CredentialItemSnapshot> copy = new ArrayList<>(items);
        Collections.sort(copy);
        this.credentialSetId = credentialSetId;
        this.sortedItems = Collections.unmodifiableList(copy);
    }

    /**
     * 获取凭据集主键 ID。
     *
     * @return 凭据集 ID
     */
    public long credentialSetId() {
        return credentialSetId;
    }

    /**
     * 获取已排序的不可变凭据项快照集合。
     *
     * @return 排序后的凭据项列表
     */
    public List<CredentialItemSnapshot> sortedItems() {
        return sortedItems;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HuashengCredentialIdentity that = (HuashengCredentialIdentity) o;
        return credentialSetId == that.credentialSetId && sortedItems.equals(that.sortedItems);
    }

    @Override
    public int hashCode() {
        return Objects.hash(credentialSetId, sortedItems);
    }

    @Override
    public String toString() {
        return "HuashengCredentialIdentity[credentialSetId=" + credentialSetId + ",itemCount=" + sortedItems.size() + "]";
    }
}
