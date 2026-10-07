package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;

import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalOnlySerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.util.Objects;
import java.util.Optional;

/**
 * 华昇订单端点（PLACE, CONFIRM, QUERY）成功响应不可变数据载荷。
 *
 * <p>职责：封装供应商订单端点返回的完整订单事实（包含必需的 orderSn、outOrderSn、num、totalPrice、status
 * 以及其余 17 个通过 Optional 强类型保真的可选事实字段）。
 *
 * <p>不变量：包可见构造，唯一生产所有者为协议解析层；5 项必需字段非空；17 项可选字段以非空 Optional 容器保真；
 * 严禁包含 RETRY_SAFE 或直接资金动作指示；toString 严格脱敏；类级禁止意外 JSON 序列化。
 *
 * <p>关键决策：普通 final 类（严禁使用 record 以免反射泄漏敏感数据或默认 toString 暴露细节）；
 * 保持原文强类型保真，不依靠日期格式化建立资金结论。
 *
 * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#53-订单事实、§15.1.3。
 */
@JsonSerialize(using = HuashengInternalOnlySerializer.class)
public final class HuashengOrderResponse {
    private final String orderSn;
    private final String outOrderSn;
    private final int num;
    private final HuashengPrice totalPrice;
    private final HuashengOrderStatus status;
    private final Optional<HuashengPrice> price;
    private final Optional<HuashengFaceAmount> amount;
    private final Optional<HuashengFaceAmount> totalAmount;
    private final Optional<HuashengRefundStatus> refundStatus;
    private final Optional<HuashengTakeStatus> isTake;
    private final Optional<Long> id;
    private final Optional<Long> brandId;
    private final Optional<String> brandName;
    private final Optional<Long> couponId;
    private final Optional<String> couponName;
    private final Optional<String> cardType;
    private final Optional<String> outMobile;
    private final Optional<String> payTime;
    private final Optional<String> takeTime;
    private final Optional<String> expireTime;
    private final Optional<String> refundTime;
    private final Optional<String> createdAt;

    HuashengOrderResponse(
            String orderSn,
            String outOrderSn,
            int num,
            HuashengPrice totalPrice,
            HuashengOrderStatus status,
            Optional<HuashengPrice> price,
            Optional<HuashengFaceAmount> amount,
            Optional<HuashengFaceAmount> totalAmount,
            Optional<HuashengRefundStatus> refundStatus,
            Optional<HuashengTakeStatus> isTake,
            Optional<Long> id,
            Optional<Long> brandId,
            Optional<String> brandName,
            Optional<Long> couponId,
            Optional<String> couponName,
            Optional<String> cardType,
            Optional<String> outMobile,
            Optional<String> payTime,
            Optional<String> takeTime,
            Optional<String> expireTime,
            Optional<String> refundTime,
            Optional<String> createdAt) {
        this.orderSn = Objects.requireNonNull(orderSn, "orderSn must not be null");
        this.outOrderSn = Objects.requireNonNull(outOrderSn, "outOrderSn must not be null");
        this.num = num;
        this.totalPrice = Objects.requireNonNull(totalPrice, "totalPrice must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.price = Objects.requireNonNull(price, "price must not be null");
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        this.totalAmount = Objects.requireNonNull(totalAmount, "totalAmount must not be null");
        this.refundStatus = Objects.requireNonNull(refundStatus, "refundStatus must not be null");
        this.isTake = Objects.requireNonNull(isTake, "isTake must not be null");
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.brandId = Objects.requireNonNull(brandId, "brandId must not be null");
        this.brandName = Objects.requireNonNull(brandName, "brandName must not be null");
        this.couponId = Objects.requireNonNull(couponId, "couponId must not be null");
        this.couponName = Objects.requireNonNull(couponName, "couponName must not be null");
        this.cardType = Objects.requireNonNull(cardType, "cardType must not be null");
        this.outMobile = Objects.requireNonNull(outMobile, "outMobile must not be null");
        this.payTime = Objects.requireNonNull(payTime, "payTime must not be null");
        this.takeTime = Objects.requireNonNull(takeTime, "takeTime must not be null");
        this.expireTime = Objects.requireNonNull(expireTime, "expireTime must not be null");
        this.refundTime = Objects.requireNonNull(refundTime, "refundTime must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    /**
     * 获取供应商订单号。
     *
     * <p>职责：返回华昇平台生成的唯一订单号。
     * <p>不变量：必填字段，非空非空白。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#53-订单事实、§15.1.3。
     *
     * @return 供应商订单号
     */
    public String orderSn() { return orderSn; }

    /**
     * 获取商户外部单号。
     *
     * <p>职责：返回本地方发起的业务请求外部单号。
     * <p>不变量：必填字段，非空非空白，与期望单号严格一致。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#53-订单事实、§15.1.3。
     *
     * @return 商户外部单号
     */
    public String outOrderSn() { return outOrderSn; }

    /**
     * 获取采购商品数量。
     *
     * <p>职责：返回订单购买数量。
     * <p>不变量：必填字段，大于等于 1，且与期望采购数量完全一致。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#53-订单事实、§15.1.3。
     *
     * @return 采购数量
     */
    public int num() { return num; }

    /**
     * 获取订单总结算成本价。
     *
     * <p>职责：返回供应商返回的该笔订单实际总成本价。
     * <p>不变量：必填字段，非空不可变 {@link HuashengPrice}；与 price 存在时满足数学一致性校验。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#52-金额、#53-订单事实。
     *
     * @return 订单总成本价
     */
    public HuashengPrice totalPrice() { return totalPrice; }

    /**
     * 获取订单当前状态。
     *
     * <p>职责：返回供应商返回的订单生命周期状态枚举。
     * <p>不变量：必填字段，非空 {@link HuashengOrderStatus}；与 refundStatus/isTake 保持矩阵相容。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#53-订单事实、§15.2.5。
     *
     * @return 订单状态枚举
     */
    public HuashengOrderStatus status() { return status; }

    /**
     * 获取单价成本（若出现）。
     *
     * <p>职责：以 Optional 容器保真返回商品单价成本。
     * <p>不变量：容器非 null；若存在则保证非空且数学上满足 price * num == totalPrice。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#52-金额、#53-订单事实。
     *
     * @return 包含单价成本的 Optional，或空 Optional
     */
    public Optional<HuashengPrice> price() { return price; }

    /**
     * 获取商品面值（若出现）。
     *
     * <p>职责：以 Optional 容器保真返回面值原文。
     * <p>不变量：容器非 null；面值仅保存原文，不推导成本或换算分值。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#52-金额、#53-订单事实。
     *
     * @return 包含面值的 Optional，或空 Optional
     */
    public Optional<HuashengFaceAmount> amount() { return amount; }

    /**
     * 获取商品总面值（若出现）。
     *
     * <p>职责：以 Optional 容器保真返回总面值原文。
     * <p>不变量：容器非 null；面值仅保存原文，不推导成本或换算分值。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#52-金额、#53-订单事实。
     *
     * @return 包含总面值的 Optional，或空 Optional
     */
    public Optional<HuashengFaceAmount> totalAmount() { return totalAmount; }

    /**
     * 获取订单退款状态（若出现）。
     *
     * <p>职责：以 Optional 容器保真返回退款状态枚举。
     * <p>不变量：容器非 null；QUERY 阶段强制存在；PLACE/CONFIRM 阶段允许缺省。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#53-订单事实、§15.2.5。
     *
     * @return 包含退款状态的 Optional，或空 Optional
     */
    public Optional<HuashengRefundStatus> refundStatus() { return refundStatus; }

    /**
     * 获取订单提卡状态（若出现）。
     *
     * <p>职责：以 Optional 容器保真返回提卡状态枚举。
     * <p>不变量：容器非 null；QUERY 阶段强制存在；PLACE/CONFIRM 阶段允许缺省。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#53-订单事实、§15.2.5。
     *
     * @return 包含提卡状态的 Optional，或空 Optional
     */
    public Optional<HuashengTakeStatus> isTake() { return isTake; }

    /**
     * 获取订单内部数字主键 ID（若出现）。
     *
     * <p>职责：以 Optional 容器保真返回供应商数据库主键 ID。
     * <p>不变量：容器非 null；若存在为有效 Long。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#53-订单事实。
     *
     * @return 包含 ID 的 Optional，或空 Optional
     */
    public Optional<Long> id() { return id; }

    /**
     * 获取品牌 ID（若出现）。
     *
     * <p>职责：以 Optional 容器保真返回品牌 ID。
     * <p>不变量：容器非 null。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#53-订单事实。
     *
     * @return 包含品牌 ID 的 Optional，或空 Optional
     */
    public Optional<Long> brandId() { return brandId; }

    /**
     * 获取品牌名称（若出现）。
     *
     * <p>职责：以 Optional 容器保真返回品牌名称。
     * <p>不变量：容器非 null。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#53-订单事实。
     *
     * @return 包含品牌名称的 Optional，或空 Optional
     */
    public Optional<String> brandName() { return brandName; }

    /**
     * 获取卡券券种 ID（若出现）。
     *
     * <p>职责：以 Optional 容器保真返回卡券券种 ID。
     * <p>不变量：容器非 null。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#53-订单事实。
     *
     * @return 包含券种 ID 的 Optional，或空 Optional
     */
    public Optional<Long> couponId() { return couponId; }

    /**
     * 获取卡券券种名称（若出现）。
     *
     * <p>职责：以 Optional 容器保真返回卡券券种名称。
     * <p>不变量：容器非 null。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#53-订单事实。
     *
     * @return 包含券种名称的 Optional，或空 Optional
     */
    public Optional<String> couponName() { return couponName; }

    /**
     * 获取卡券类型文本（若出现）。
     *
     * <p>职责：以 Optional 容器保真返回卡券类型文本。
     * <p>不变量：容器非 null。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#53-订单事实。
     *
     * @return 包含卡券类型文本的 Optional，或空 Optional
     */
    public Optional<String> cardType() { return cardType; }

    /**
     * 获取外部手机号（若出现）。
     *
     * <p>职责：以 Optional 容器保真返回下单手机号。
     * <p>不变量：容器非 null；敏感信息仅在内存保真，toString 严格遮盖。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#53-订单事实。
     *
     * @return 包含手机号的 Optional，或空 Optional
     */
    public Optional<String> outMobile() { return outMobile; }

    /**
     * 获取支付时间文本（若出现）。
     *
     * <p>职责：以 Optional 容器保真返回支付时间原始字符串。
     * <p>不变量：容器非 null；不执行格式化推导。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#53-订单事实。
     *
     * @return 包含支付时间的 Optional，或空 Optional
     */
    public Optional<String> payTime() { return payTime; }

    /**
     * 获取提卡时间文本（若出现）。
     *
     * <p>职责：以 Optional 容器保真返回提卡时间原始字符串。
     * <p>不变量：容器非 null。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#53-订单事实。
     *
     * @return 包含提卡时间的 Optional，或空 Optional
     */
    public Optional<String> takeTime() { return takeTime; }

    /**
     * 获取失效时间文本（若出现）。
     *
     * <p>职责：以 Optional 容器保真返回失效时间原始字符串。
     * <p>不变量：容器非 null。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#53-订单事实。
     *
     * @return 包含失效时间的 Optional，或空 Optional
     */
    public Optional<String> expireTime() { return expireTime; }

    /**
     * 获取退款时间文本（若出现）。
     *
     * <p>职责：以 Optional 容器保真返回退款时间原始字符串。
     * <p>不变量：容器非 null。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#53-订单事实。
     *
     * @return 包含退款时间的 Optional，或空 Optional
     */
    public Optional<String> refundTime() { return refundTime; }

    /**
     * 获取创建时间文本（若出现）。
     *
     * <p>职责：以 Optional 容器保真返回创建时间原始字符串。
     * <p>不变量：容器非 null。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#53-订单事实。
     *
     * @return 包含创建时间的 Optional，或空 Optional
     */
    public Optional<String> createdAt() { return createdAt; }

    /**
     * 返回严格脱敏的字符串表示，仅包含状态与数量，杜绝泄露手机号、单号等隐私事实。
     *
     * @return 结构安全的脱敏文本
     */
    @Override
    public String toString() {
        return "HuashengOrderResponse[status=" + status.name() + ",num=" + num + ",[redacted]]";
    }
}
