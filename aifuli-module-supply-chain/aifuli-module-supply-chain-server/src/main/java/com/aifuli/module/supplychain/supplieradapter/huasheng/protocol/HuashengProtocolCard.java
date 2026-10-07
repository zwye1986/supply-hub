package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;

import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalOnlySerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.util.Objects;

/**
 * 华昇协议卡券不可变值对象。
 *
 * <p>职责：封装从供应商 TAKE 端点解析返回的单张卡券完整数据事实（包含元数据、三态字段及强类型载荷）。
 *
 * <p>不变量：包可见构造，唯一生产所有者为协议解析层；toString 严格脱敏，不输出凭证/卡密/密码/链接；
 * 类级禁止意外 JSON 序列化；持有强类型 {@link HuashengCardPayload}。
 *
 * <p>关键决策：普通 final 类（严禁使用 record 以免反射泄漏或默认 toString 暴露敏感凭证）；
 * id、code、pass、crc、url、urlPass、validTime 等字段均以 {@link HuashengField} 三态保真。
 *
 * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
 */
@JsonSerialize(using = HuashengInternalOnlySerializer.class)
public final class HuashengProtocolCard {
    private final HuashengField<Long> id;
    private final HuashengCardType type;
    private final HuashengField<String> code;
    private final HuashengField<String> pass;
    private final HuashengField<String> crc;
    private final HuashengField<String> url;
    private final HuashengField<String> urlPass;
    private final HuashengField<String> validTime;
    private final HuashengCardPayload payload;

    HuashengProtocolCard(
            HuashengField<Long> id,
            HuashengCardType type,
            HuashengField<String> code,
            HuashengField<String> pass,
            HuashengField<String> crc,
            HuashengField<String> url,
            HuashengField<String> urlPass,
            HuashengField<String> validTime,
            HuashengCardPayload payload) {
        this.id = Objects.requireNonNull(id, "id field must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.code = Objects.requireNonNull(code, "code field must not be null");
        this.pass = Objects.requireNonNull(pass, "pass field must not be null");
        this.crc = Objects.requireNonNull(crc, "crc field must not be null");
        this.url = Objects.requireNonNull(url, "url field must not be null");
        this.urlPass = Objects.requireNonNull(urlPass, "urlPass field must not be null");
        this.validTime = Objects.requireNonNull(validTime, "validTime field must not be null");
        this.payload = Objects.requireNonNull(payload, "payload must not be null");
    }

    /**
     * 获取供应商卡片主键 ID（三态容器）。
     *
     * <p>职责：保真记录供应商返回的卡片主键 ID。
     * <p>不变量：三态容器非 null；若存在则为正整数 Long。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
     *
     * @return 卡片 ID 三态容器
     */
    public HuashengField<Long> id() { return id; }

    /**
     * 获取卡券交付类型枚举。
     *
     * <p>职责：标识当前卡片的交付形态（201~304）。
     * <p>不变量：保证非 null，且与 {@link #payload()} 的具体实现类型严格一致。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
     *
     * @return 卡券交付类型枚举
     */
    public HuashengCardType type() { return type; }

    /**
     * 获取卡号（三态容器）。
     *
     * <p>职责：以三态容器保真存储供应商卡号字段。
     * <p>不变量：三态容器非 null。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
     *
     * @return 卡号三态容器
     */
    public HuashengField<String> code() { return code; }

    /**
     * 获取卡密（三态容器）。
     *
     * <p>职责：以三态容器保真存储供应商卡密字段。
     * <p>不变量：三态容器非 null。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
     *
     * @return 卡密三态容器
     */
    public HuashengField<String> pass() { return pass; }

    /**
     * 获取 CRC 校验码（三态容器）。
     *
     * <p>职责：以三态容器保真存储供应商 CRC 字段。
     * <p>不变量：三态容器非 null。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
     *
     * @return CRC 三态容器
     */
    public HuashengField<String> crc() { return crc; }

    /**
     * 获取直连/充值链接 URL（三态容器）。
     *
     * <p>职责：以三态容器保真存储供应商 URL 字段。
     * <p>不变量：三态容器非 null。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
     *
     * @return 链接 URL 三态容器
     */
    public HuashengField<String> url() { return url; }

    /**
     * 获取链接密码/提取码（三态容器）。
     *
     * <p>职责：以三态容器保真存储供应商 urlPass 字段。
     * <p>不变量：三态容器非 null。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
     *
     * @return 链接密码三态容器
     */
    public HuashengField<String> urlPass() { return urlPass; }

    /**
     * 获取卡券有效期（三态容器）。
     *
     * <p>职责：以三态容器保真存储供应商 validTime 文本。
     * <p>不变量：三态容器非 null。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
     *
     * @return 有效期三态容器
     */
    public HuashengField<String> validTime() { return validTime; }

    /**
     * 获取强类型卡券载荷。
     *
     * <p>职责：返回与当前卡型绑定的强类型数据载荷对象，提供编译期安全的值访问。
     * <p>不变量：保证非 null，且类型与 {@link #type()} 一一对应。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
     *
     * @return 强类型卡券载荷接口实例
     */
    public HuashengCardPayload payload() { return payload; }

    static HuashengCardPayload.Card201 createPayload201(String code, String pass) {
        return new HuashengCardPayload.Card201(code, pass);
    }

    static HuashengCardPayload.Card202 createPayload202(HuashengField<String> code, HuashengField<String> pass) {
        return new HuashengCardPayload.Card202(code, pass);
    }

    static HuashengCardPayload.Card203 createPayload203(String code, String pass, String crc) {
        return new HuashengCardPayload.Card203(code, pass, crc);
    }

    static HuashengCardPayload.Card301 createPayload301(String url) {
        return new HuashengCardPayload.Card301(url);
    }

    static HuashengCardPayload.Card302 createPayload302(String url, String urlPass) {
        return new HuashengCardPayload.Card302(url, urlPass);
    }

    static HuashengCardPayload.Card303 createPayload303(String code, String url, String urlPass) {
        return new HuashengCardPayload.Card303(code, url, urlPass);
    }

    static HuashengCardPayload.Card304 createPayload304(String code, String url) {
        return new HuashengCardPayload.Card304(code, url);
    }

    /**
     * 返回严格脱敏的字符串表示，杜绝泄露卡密、卡号或链接。
     *
     * @return 仅包含卡型与脱敏标记的文本
     */
    @Override
    public String toString() {
        return "HuashengProtocolCard[" + type.name() + "][redacted]";
    }
}
