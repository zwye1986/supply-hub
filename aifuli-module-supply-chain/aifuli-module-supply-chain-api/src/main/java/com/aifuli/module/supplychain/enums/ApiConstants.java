package com.aifuli.module.supplychain.enums;

import com.aifuli.framework.common.enums.RpcConstants;

/**
 * 供应链中台 RPC 契约常量。
 */
public final class ApiConstants {

    /** 采购商调用供应链中台时使用的服务名。 */
    public static final String NAME = "${aifuli.supply-chain.rpc.service-name:supply-chain-server}";

    /** 中台通过 RPC 向一期 SaaS 商城投递事件时使用的服务名。 */
    public static final String BUYER_SERVICE_NAME =
            "${aifuli.supply-chain.rpc.buyer-service-name:mall-server}";

    public static final String PREFIX = RpcConstants.RPC_API_PREFIX + "/supply-chain";

    public static final String VERSION = "1.0.0";

    private ApiConstants() {
    }
}
