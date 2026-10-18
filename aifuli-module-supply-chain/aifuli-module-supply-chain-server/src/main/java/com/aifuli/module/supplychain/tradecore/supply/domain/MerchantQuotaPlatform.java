package com.aifuli.module.supplychain.tradecore.supply.domain;

/** 真实供应商平台额度域，不随连接器/供应商身份行复制而增加额度。设计见商户共享限额设计§4.1。 */
public enum MerchantQuotaPlatform {
    RENYUE, QNM, SZ_CARD;

    /** 仅代码内三种协议选择；未知类型不猜平台，旧协议是否启用仍由单步合同决定。 */
    public static MerchantQuotaPlatform forAdapter(String type) {
        return switch (type) {
            case "RENYUE_RECHARGE_V2" -> RENYUE;
            case "QNM_GAME_ORDER_V1" -> QNM;
            case "SZ_CARD_EQUITY_V1" -> SZ_CARD;
            default -> null;
        };
    }
}
