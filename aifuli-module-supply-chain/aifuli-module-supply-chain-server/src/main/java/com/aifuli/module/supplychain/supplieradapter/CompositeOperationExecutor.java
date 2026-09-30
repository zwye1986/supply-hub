package com.aifuli.module.supplychain.supplieradapter;

import com.aifuli.module.supplychain.border.fulfillment.domain.OperationExecutorPort;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcome;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationTicket;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 复合履约操作执行器：根据 {@link OperationTicket#adapterType()} 分发至对应的适配器实现。
 *
 * <p>作为 {@link OperationExecutorPort} 的主 Bean（{@code @Primary}），统一面向履约应用层暴露，
 * 底层注册并路由至具体的供应商适配器（如 {@code QnmSupplierAdapter}）。</p>
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#731-operationexecutorport-契约模型}。</p>
 */
@Primary
@Component
public class CompositeOperationExecutor implements OperationExecutorPort {

    private final List<OperationExecutorPort> delegates;

    public CompositeOperationExecutor(List<OperationExecutorPort> delegates) {
        // 过滤自身，避免递归注入，并保证 null 安全
        if (delegates == null) {
            this.delegates = List.of();
        } else {
            this.delegates = delegates.stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(d -> !(d instanceof CompositeOperationExecutor))
                    .toList();
        }
    }

    @Override
    public boolean supports(String adapterType) {
        if (adapterType == null || adapterType.isBlank()) {
            return false;
        }
        return delegates.stream().anyMatch(d -> d.supports(adapterType));
    }

    @Override
    public OperationOutcome execute(OperationTicket ticket) {
        Objects.requireNonNull(ticket, "ticket must not be null");
        String adapterType = ticket.adapterType();
        for (OperationExecutorPort delegate : delegates) {
            if (delegate.supports(adapterType)) {
                return delegate.execute(ticket);
            }
        }
        throw new IllegalArgumentException("No OperationExecutorPort found for adapterType: " + adapterType);
    }
}
