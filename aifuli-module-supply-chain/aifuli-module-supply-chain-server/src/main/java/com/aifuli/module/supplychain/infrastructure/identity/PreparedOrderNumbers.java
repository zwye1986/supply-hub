package com.aifuli.module.supplychain.infrastructure.identity;

/**
 * 事务外预分配的不可变订单身份；只允许24位ASCII数字，单号始终以字符串传递。 billno在仅创建总单时为空；双号用途要求两个身份不同。 设计见 {@code
 * docs/superpowers/specs/2026-10-13-supply-chain-order-number-design.md#23-业务身份}。
 */
public record PreparedOrderNumbers(String orderNo, String billno) {
  public PreparedOrderNumbers {
    validate(orderNo);
    if (billno != null) {
      validate(billno);
      if (orderNo.equals(billno)) throw new IllegalArgumentException("订单号与商户单号必须不同");
    }
  }

  private static void validate(String value) {
    if (value == null || !value.matches("[0-9]{24}"))
      throw new IllegalArgumentException("预分配编号必须为24位ASCII数字");
  }
}
