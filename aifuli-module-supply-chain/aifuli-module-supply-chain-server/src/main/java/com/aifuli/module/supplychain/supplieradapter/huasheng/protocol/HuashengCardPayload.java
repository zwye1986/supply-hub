package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;

import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalOnlySerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.util.Objects;

/**
 * 华昇协议卡券载荷强类型封闭接口（7 种具体卡型 Card201~Card304）。
 *
 * <p>职责：为已通过类型校验的卡片提供强类型的载荷访问，保证所需交付字段（如卡号、密码、CRC、URL 等）
 * 均在编译期和运行期获得完整事实保证。
 *
 * <p>不变量：七种类型 sealed 密封；所有具体卡型构造包可见（所有者为协议卡片层）；
 * toString 严格脱敏，不输出任何凭证；类级禁止意外 JSON 序列化。
 *
 * <p>关键决策：Card202 同时保留可能出现的两个字段（至少一个存在），Card203/Card303 分别保留 crc 与 urlPass；
 * 交付内容不做无授权的解密、转换或裁剪。
 *
 * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
 */
@JsonSerialize(using = HuashengInternalOnlySerializer.class)
public sealed interface HuashengCardPayload permits
        HuashengCardPayload.Card201,
        HuashengCardPayload.Card202,
        HuashengCardPayload.Card203,
        HuashengCardPayload.Card301,
        HuashengCardPayload.Card302,
        HuashengCardPayload.Card303,
        HuashengCardPayload.Card304 {

    @JsonSerialize(using = HuashengInternalOnlySerializer.class)
    final class Card201 implements HuashengCardPayload {
        private final String code;
        private final String pass;

        Card201(String code, String pass) {
            this.code = Objects.requireNonNull(code, "code must not be null");
            this.pass = Objects.requireNonNull(pass, "pass must not be null");
        }

        /**
         * 获取卡券卡号。
         *
         * <p>职责：返回 T201 卡型必填的卡号原文。
         * <p>不变量：保证非 null 且非空白。
         * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
         *
         * @return 卡号原文
         */
        public String code() { return code; }

        /**
         * 获取卡券卡密。
         *
         * <p>职责：返回 T201 卡型必填的卡密原文。
         * <p>不变量：保证非 null 且非空白。
         * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
         *
         * @return 卡密原文
         */
        public String pass() { return pass; }

        /**
         * 安全脱敏输出，严禁输出卡号与卡密。
         *
         * @return 固定脱敏标识
         */
        @Override
        public String toString() {
            return "HuashengCardPayload[T201][redacted]";
        }
    }

    @JsonSerialize(using = HuashengInternalOnlySerializer.class)
    final class Card202 implements HuashengCardPayload {
        private final HuashengField<String> code;
        private final HuashengField<String> pass;

        Card202(HuashengField<String> code, HuashengField<String> pass) {
            this.code = Objects.requireNonNull(code, "code must not be null");
            this.pass = Objects.requireNonNull(pass, "pass must not be null");
        }

        /**
         * 获取卡号三态容器。
         *
         * <p>职责：返回 T202 卡型卡号字段的三态状态。
         * <p>不变量：三态容器非 null；T202 保证 code 与 pass 至少有一个为有效非空值。
         * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
         *
         * @return 卡号三态容器
         */
        public HuashengField<String> code() { return code; }

        /**
         * 获取卡密三态容器。
         *
         * <p>职责：返回 T202 卡型卡密字段的三态状态。
         * <p>不变量：三态容器非 null；T202 保证 code 与 pass 至少有一个为有效非空值。
         * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
         *
         * @return 卡密三态容器
         */
        public HuashengField<String> pass() { return pass; }

        /**
         * 安全脱敏输出，严禁输出卡号与卡密。
         *
         * @return 固定脱敏标识
         */
        @Override
        public String toString() {
            return "HuashengCardPayload[T202][redacted]";
        }
    }

    @JsonSerialize(using = HuashengInternalOnlySerializer.class)
    final class Card203 implements HuashengCardPayload {
        private final String code;
        private final String pass;
        private final String crc;

        Card203(String code, String pass, String crc) {
            this.code = Objects.requireNonNull(code, "code must not be null");
            this.pass = Objects.requireNonNull(pass, "pass must not be null");
            this.crc = Objects.requireNonNull(crc, "crc must not be null");
        }

        /**
         * 获取卡券卡号。
         *
         * <p>职责：返回 T203 卡型必填的卡号原文。
         * <p>不变量：保证非 null 且非空白。
         * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
         *
         * @return 卡号原文
         */
        public String code() { return code; }

        /**
         * 获取卡券卡密。
         *
         * <p>职责：返回 T203 卡型必填的卡密原文。
         * <p>不变量：保证非 null 且非空白。
         * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
         *
         * @return 卡密原文
         */
        public String pass() { return pass; }

        /**
         * 获取卡券 CRC 校验码。
         *
         * <p>职责：返回 T203 卡型必填的 CRC 校验码。
         * <p>不变量：保证非 null 且非空白。
         * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
         *
         * @return CRC 校验码
         */
        public String crc() { return crc; }

        /**
         * 安全脱敏输出，严禁输出卡号、卡密与 CRC。
         *
         * @return 固定脱敏标识
         */
        @Override
        public String toString() {
            return "HuashengCardPayload[T203][redacted]";
        }
    }

    @JsonSerialize(using = HuashengInternalOnlySerializer.class)
    final class Card301 implements HuashengCardPayload {
        private final String url;

        Card301(String url) {
            this.url = Objects.requireNonNull(url, "url must not be null");
        }

        /**
         * 获取直充/直连 URL。
         *
         * <p>职责：返回 T301 卡型必填的 URL。
         * <p>不变量：保证非 null 且非空白。
         * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
         *
         * @return 卡券链接 URL
         */
        public String url() { return url; }

        /**
         * 安全脱敏输出，严禁输出链接 URL。
         *
         * @return 固定脱敏标识
         */
        @Override
        public String toString() {
            return "HuashengCardPayload[T301][redacted]";
        }
    }

    @JsonSerialize(using = HuashengInternalOnlySerializer.class)
    final class Card302 implements HuashengCardPayload {
        private final String url;
        private final String urlPass;

        Card302(String url, String urlPass) {
            this.url = Objects.requireNonNull(url, "url must not be null");
            this.urlPass = Objects.requireNonNull(urlPass, "urlPass must not be null");
        }

        /**
         * 获取直连 URL。
         *
         * <p>职责：返回 T302 卡型必填的 URL。
         * <p>不变量：保证非 null 且非空白。
         * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
         *
         * @return 卡券链接 URL
         */
        public String url() { return url; }

        /**
         * 获取链接密码（提取码）。
         *
         * <p>职责：返回 T302 卡型必填的链接密码。
         * <p>不变量：保证非 null 且非空白。
         * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
         *
         * @return 链接密码原文
         */
        public String urlPass() { return urlPass; }

        /**
         * 安全脱敏输出，严禁输出链接与提取码。
         *
         * @return 固定脱敏标识
         */
        @Override
        public String toString() {
            return "HuashengCardPayload[T302][redacted]";
        }
    }

    @JsonSerialize(using = HuashengInternalOnlySerializer.class)
    final class Card303 implements HuashengCardPayload {
        private final String code;
        private final String url;
        private final String urlPass;

        Card303(String code, String url, String urlPass) {
            this.code = Objects.requireNonNull(code, "code must not be null");
            this.url = Objects.requireNonNull(url, "url must not be null");
            this.urlPass = Objects.requireNonNull(urlPass, "urlPass must not be null");
        }

        /**
         * 获取卡号。
         *
         * <p>职责：返回 T303 卡型必填的卡号。
         * <p>不变量：保证非 null 且非空白。
         * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
         *
         * @return 卡号原文
         */
        public String code() { return code; }

        /**
         * 获取直连 URL。
         *
         * <p>职责：返回 T303 卡型必填的 URL。
         * <p>不变量：保证非 null 且非空白。
         * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
         *
         * @return 卡券链接 URL
         */
        public String url() { return url; }

        /**
         * 获取链接密码（提取码）。
         *
         * <p>职责：返回 T303 卡型必填的链接密码。
         * <p>不变量：保证非 null 且非空白。
         * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
         *
         * @return 链接密码原文
         */
        public String urlPass() { return urlPass; }

        /**
         * 安全脱敏输出，严禁输出卡号、链接与提取码。
         *
         * @return 固定脱敏标识
         */
        @Override
        public String toString() {
            return "HuashengCardPayload[T303][redacted]";
        }
    }

    @JsonSerialize(using = HuashengInternalOnlySerializer.class)
    final class Card304 implements HuashengCardPayload {
        private final String code;
        private final String url;

        Card304(String code, String url) {
            this.code = Objects.requireNonNull(code, "code must not be null");
            this.url = Objects.requireNonNull(url, "url must not be null");
        }

        /**
         * 获取卡号。
         *
         * <p>职责：返回 T304 卡型必填的卡号。
         * <p>不变量：保证非 null 且非空白。
         * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
         *
         * @return 卡号原文
         */
        public String code() { return code; }

        /**
         * 获取直连 URL。
         *
         * <p>职责：返回 T304 卡型必填的 URL。
         * <p>不变量：保证非 null 且非空白。
         * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
         *
         * @return 卡券链接 URL
         */
        public String url() { return url; }

        /**
         * 安全脱敏输出，严禁输出卡号与链接。
         *
         * @return 固定脱敏标识
         */
        @Override
        public String toString() {
            return "HuashengCardPayload[T304][redacted]";
        }
    }
}
