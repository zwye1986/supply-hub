package com.aifuli.module.supplychain.infrastructure.identity;

/**
 * 封闭的创建用途；批次数不得由外部请求决定。设计见 {@code
 * docs/superpowers/specs/2026-10-13-supply-chain-order-number-design.md#42-申请号段的短事务}。
 */
public enum OrderNoPurpose {
  ORDER_ONLY(1),
  ORDER_AND_BILL(2);
  private final int count;

  OrderNoPurpose(int count) {
    this.count = count;
  }

  public int count() {
    return count;
  }
}
