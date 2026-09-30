package com.aifuli.module.supplychain.tradecore.account.domain;

/**
 * 资金预留业务引用类型，对应 {@code sc_fund_reservation.reference_type}。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#721-标准履约稳定编码与-java-领域枚举}。</p>
 */
public enum FundReferenceType {
    CARD_ORDER,
    O2O_ORDER,
    INSTORE_ORDER;

    public String code() {
        return name();
    }
}
