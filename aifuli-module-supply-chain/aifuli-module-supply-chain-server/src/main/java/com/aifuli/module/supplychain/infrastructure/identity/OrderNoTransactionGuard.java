package com.aifuli.module.supplychain.infrastructure.identity;

import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 取号永不挂起已有业务事务或再借第二个连接；热路径同样严格拒绝误用。 设计见 {@code
 * docs/superpowers/specs/2026-10-13-supply-chain-order-number-design.md#42-申请号段的短事务}。
 */
final class OrderNoTransactionGuard {
  private OrderNoTransactionGuard() {}

  static void check(DataSource dataSource) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.hasResource(dataSource))
      throw new IllegalStateException("订单编号必须在实际事务及供应链绑定连接之外分配");
  }
}
