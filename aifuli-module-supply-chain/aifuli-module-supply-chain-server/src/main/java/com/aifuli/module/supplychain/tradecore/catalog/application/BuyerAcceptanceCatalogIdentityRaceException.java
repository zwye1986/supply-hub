package com.aifuli.module.supplychain.tradecore.catalog.application;

/**
 * 封闭LST身份唯一键冲突的控制信号，保留原技术异常完整cause，强制退出整批worker事务。
 * 编排层只有在真实代理完全回滚后才能用其中买家/SKU/尝试编码发起独立probe并有界重跑。
 * 这些字段仅用于审计和重探测，不是已提交身份；尤其attemptedCode可能属于已回滚BPD，
 * 禁止把它作为选品成功结果或继续写外键。BPD重复及其它SQL/JSON失败不属于本异常的重试范围。
 * 设计见 {@code docs/superpowers/specs/2026-10-04-buyer-selection-acceptance-catalog-bridge-design.md#34-批次事务边界真实调用者与竞争重试}
 * §3.4「批次事务边界、真实调用者与竞争重试」。
 */
public final class BuyerAcceptanceCatalogIdentityRaceException extends RuntimeException {
    private final Long buyerId;
    private final Long platformSkuId;
    private final String attemptedCode;
    public BuyerAcceptanceCatalogIdentityRaceException(Long buyerId, Long platformSkuId, String attemptedCode, Throwable cause) {
        super("受理目录身份唯一竞争", cause);
        this.buyerId = buyerId;
        this.platformSkuId = platformSkuId;
        this.attemptedCode = attemptedCode;
    }
    public Long buyerId() { return buyerId; }
    public Long platformSkuId() { return platformSkuId; }
    public String attemptedCode() { return attemptedCode; }
}
