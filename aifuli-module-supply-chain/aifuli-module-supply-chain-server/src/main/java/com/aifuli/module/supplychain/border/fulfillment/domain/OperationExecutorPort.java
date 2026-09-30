package com.aifuli.module.supplychain.border.fulfillment.domain;

/**
 * 履约外部操作执行出站端口：执行不可变 OperationTicket 并返回标准 OperationOutcome。
 *
 * <p>遵循端口与适配器（Hexagonal Architecture）原则，履约领域仅依赖本端口，
 * 具体供应商适配器实现位于 {@code com.aifuli.module.supplychain.supplieradapter}。
 * 适配器严禁依赖 {@code ..border..dal..} 或直接读写订单库与账本。</p>
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#731-operationexecutorport-契约模型}
 * 以及 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#10-adapter-元数据调用审计与敏感资产}。</p>
 */
public interface OperationExecutorPort {

    /**
     * 判断当前适配器是否支持执行该类型的协议（例如 {@code QNM_GAME_ORDER_V1}）。
     *
     * @param adapterType 适配器类型代码
     * @return true 若支持
     */
    boolean supports(String adapterType);

    /**
     * 执行不可变履约操作输入凭证并返回封闭标准结果。
     *
     * @param ticket 不可变履约操作凭据
     * @return 标准化操作执行结果
     */
    OperationOutcome execute(OperationTicket ticket);
}
