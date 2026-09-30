package com.aifuli.module.supplychain.integration.buyer;

/**
 * 业务冻结的采购项目集成档案版本已被显式安全退役。
 *
 * <p>调用方应将该异常与普通暂时不可用区分处理，且不得继续读取该版本的能力或凭据配置。
 * 设计见
 * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#5-项目集成档案解析}。</p>
 */
public final class BuyerIntegrationProfileRetiredException extends IllegalStateException {

    public BuyerIntegrationProfileRetiredException(Long profileVersionId) {
        super("Buyer integration profile version is retired: " + profileVersionId);
    }
}
