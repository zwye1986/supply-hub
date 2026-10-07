package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;

import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalOnlySerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.util.Objects;

/**
 * 华昇响应解析两层事实密封载荷（区分业务拒绝 Business 与有效数据 Data）。
 *
 * <p>职责：在遵守底层架构 carrier（InternalAttempt 密封于 Parsed/Failed 两分支）约束的前提下，
 * 将供应商业务拒绝（code != 0）正确解耦为“成功解析了供应商 envelope 业务事实”（Parsed(Business)），
 * 避免伪造系统异常 cause。
 *
 * <p>不变量：两分支 sealed 密封；构造包可见；类级禁止意外 JSON 序列化；toString 脱敏。
 *
 * <p>关键决策：禁止给底层通信 carrier 增加第三个分支破坏架构断言；两层密封模式实现业务事实与通信容器解耦。
 *
 * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#1514-业务拒绝两层载荷。
 *
 * @param <T> 成功解析的数据载荷类型
 */
@JsonSerialize(using = HuashengInternalOnlySerializer.class)
public sealed interface HuashengParseFact<T> permits HuashengParseFact.Business, HuashengParseFact.Data {

    @JsonSerialize(using = HuashengInternalOnlySerializer.class)
    final class Business<T> implements HuashengParseFact<T> {
        private final HuashengBusinessError error;

        Business(HuashengBusinessError error) {
            this.error = Objects.requireNonNull(error, "error must not be null");
        }

        /**
         * 获取业务拒绝错误对象。
         *
         * <p>职责：读取已解析的供应商业务错误事实。
         * <p>不变量：保证非 null，包含合法的非零 rawCode 与匹配的错误码枚举。
         * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#1514-业务拒绝两层载荷。
         *
         * @return 业务错误对象
         */
        public HuashengBusinessError error() {
            return error;
        }

        /**
         * 安全脱敏输出，仅显示错误结构。
         *
         * @return 脱敏文本
         */
        @Override
        public String toString() {
            return "HuashengParseFact[business=" + error + "]";
        }
    }

    @JsonSerialize(using = HuashengInternalOnlySerializer.class)
    final class Data<T> implements HuashengParseFact<T> {
        private final T data;

        Data(T data) {
            this.data = Objects.requireNonNull(data, "data must not be null");
        }

        /**
         * 获取成功解析的数据载荷。
         *
         * <p>职责：读取强类型的成功响应数据对象。
         * <p>不变量：保证非 null。
         * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#1514-业务拒绝两层载荷。
         *
         * @return 成功解析的数据对象
         */
        public T data() {
            return data;
        }

        /**
         * 安全脱敏输出，避免暴露具体数据载荷。
         *
         * @return 固定字面量 "HuashengParseFact[data]"
         */
        @Override
        public String toString() {
            return "HuashengParseFact[data]";
        }
    }
}
