package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;

import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalOnlySerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.util.Objects;
import java.util.Optional;

/**
 * 华昇协议卡片字段三态容器（缺失 ABSENT、显式空 NULL、有效值 VALUE）。
 *
 * <p>职责：精确区分协议响应中字段的未出现（ABSENT）、显式 null（NULL）与实际值（VALUE），
 * 避免标准 Optional 将缺失与 null 混淆。
 *
 * <p>不变量：三态互斥；VALUE 保证值非 null；类级禁止意外 JSON 序列化；toString 脱敏。
 *
 * <p>关键决策：采用 sealed interface 密封在三类具体不可变实现，构造包可见，所有者为协议解析层。
 *
 * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
 *
 * @param <T> 字段值类型
 */
@JsonSerialize(using = HuashengInternalOnlySerializer.class)
public sealed interface HuashengField<T> permits HuashengField.Absent, HuashengField.Null, HuashengField.Value {

    /**
     * 创建表示字段在 JSON 中完全未出现的 Absent 实例。
     *
     * <p>职责：精确表达协议响应中字段缺失的语义，与显式 null 形成严格三态区分。
     *
     * <p>不变量：返回单例不可变对象；{@link #isPresent()} 恒为 false；{@link #asOptional()} 恒为 empty。
     *
     * <p>关键决策：静态工厂封装，隐藏内部实现类构造。
     *
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
     *
     * @param <T> 字段值类型
     * @return Absent 状态字段容器
     */
    static <T> HuashengField<T> absent() {
        @SuppressWarnings("unchecked")
        HuashengField<T> instance = (HuashengField<T>) Absent.INSTANCE;
        return instance;
    }

    /**
     * 创建表示字段在 JSON 中显式为 null 的 Null 实例。
     *
     * <p>职责：精确表达协议响应中字段明确传输为 JSON null 的语义。
     *
     * <p>不变量：返回单例不可变对象；{@link #isPresent()} 恒为 false；{@link #asOptional()} 恒为 empty。
     *
     * <p>关键决策：显式保留供应商传输的 null 事实，与字段缺失（Absent）在卡片保真层区分。
     *
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
     *
     * @param <T> 字段值类型
     * @return Null 状态字段容器
     */
    static <T> HuashengField<T> ofNull() {
        @SuppressWarnings("unchecked")
        HuashengField<T> instance = (HuashengField<T>) Null.INSTANCE;
        return instance;
    }

    /**
     * 创建包含有效非空值的 Value 实例。
     *
     * <p>职责：包装成功解析且非 null 的字段值。
     *
     * <p>不变量：入参 value 严格不能为 null，否则立即抛出 NullPointerException；{@link #isPresent()} 恒为 true；
     * {@link #asOptional()} 恒包含该值。
     *
     * <p>关键决策：禁止以 null 构造 Value 状态，确保状态互斥。
     *
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
     *
     * @param value 字段实际值，非空
     * @param <T> 字段值类型
     * @return Value 状态字段容器
     */
    static <T> HuashengField<T> ofValue(T value) {
        return new Value<>(Objects.requireNonNull(value, "value must not be null"));
    }

    /**
     * 判断字段是否包含有效值（即是否为 Value 状态）。
     *
     * <p>职责：提供统一的有值判别入口。
     *
     * <p>不变量：仅当状态为 {@link Value} 时返回 true；{@link Absent} 与 {@link Null} 恒返回 false。
     *
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
     *
     * @return 存在有效值返回 true，否则返回 false
     */
    boolean isPresent();

    /**
     * 将三态字段转换为标准 Java {@link Optional}。
     *
     * <p>职责：兼容标准 Java 可空容器消费，在不关心缺失与 null 差别的调用点平滑过渡。
     *
     * <p>不变量：仅 {@link Value} 返回包装该值的 Optional；{@link Absent} 与 {@link Null} 均映射为 {@link Optional#empty()}。
     *
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
     *
     * @return 包含值的 Optional，或空 Optional
     */
    Optional<T> asOptional();

    @JsonSerialize(using = HuashengInternalOnlySerializer.class)
    final class Absent<T> implements HuashengField<T> {
        static final Absent<?> INSTANCE = new Absent<>();

        Absent() {}

        /**
         * Absent 状态恒无值。
         *
         * @return 恒返回 false
         */
        @Override
        public boolean isPresent() {
            return false;
        }

        /**
         * Absent 状态转换为 Optional 时恒为空。
         *
         * @return 恒返回 {@link Optional#empty()}
         */
        @Override
        public Optional<T> asOptional() {
            return Optional.empty();
        }

        /**
         * 返回安全的脱敏字符串表达。
         *
         * @return 固定字面量 "HuashengField[ABSENT]"
         */
        @Override
        public String toString() {
            return "HuashengField[ABSENT]";
        }
    }

    @JsonSerialize(using = HuashengInternalOnlySerializer.class)
    final class Null<T> implements HuashengField<T> {
        static final Null<?> INSTANCE = new Null<>();

        Null() {}

        /**
         * Null 状态恒无值。
         *
         * @return 恒返回 false
         */
        @Override
        public boolean isPresent() {
            return false;
        }

        /**
         * Null 状态转换为 Optional 时恒为空。
         *
         * @return 恒返回 {@link Optional#empty()}
         */
        @Override
        public Optional<T> asOptional() {
            return Optional.empty();
        }

        /**
         * 返回安全的脱敏字符串表达。
         *
         * @return 固定字面量 "HuashengField[NULL]"
         */
        @Override
        public String toString() {
            return "HuashengField[NULL]";
        }
    }

    @JsonSerialize(using = HuashengInternalOnlySerializer.class)
    final class Value<T> implements HuashengField<T> {
        private final T value;

        Value(T value) {
            this.value = Objects.requireNonNull(value, "value must not be null");
        }

        /**
         * 获取包装的字段有效值。
         *
         * <p>职责：提供对非空有效值的直接读取。
         *
         * <p>不变量：返回值保证非 null。
         *
         * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
         *
         * @return 字段值
         */
        public T value() {
            return value;
        }

        /**
         * Value 状态恒有值。
         *
         * @return 恒返回 true
         */
        @Override
        public boolean isPresent() {
            return true;
        }

        /**
         * Value 状态转换为包装该值的 Optional。
         *
         * @return 包含该值的 Optional
         */
        @Override
        public Optional<T> asOptional() {
            return Optional.of(value);
        }

        /**
         * 返回安全的脱敏字符串表达，隐藏具体敏感值。
         *
         * @return 固定字面量 "HuashengField[VALUE]"
         */
        @Override
        public String toString() {
            return "HuashengField[VALUE]";
        }
    }
}
