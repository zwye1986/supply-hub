package com.aifuli.module.supplychain.border.fulfillment.application;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 标准履约公网回调基础域名。与 ConnectorVersion.callbackPath 拼接为 QNM {@code notifyurl}。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#732-task-11-任务执行器接入状态机推进与资产资金闭环}。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "aifuli.supply-chain.fulfillment")
public class FulfillmentCallbackProperties {

    /**
     * 当前环境公网回调基础域名，例如 {@code https://supply.example.com}。
     * 未配置时 {@code notifyurl} 为空字符串，不得伪造硬编码地址。
     */
    private String publicCallbackBaseUrl = "";
}
