package com.aifuli.module.supplychain.border.instore.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 到店购支付流水 DO，对应 {@code sc_instore_payment_flow}。
 *
 * <p>承载旅通 trade_id 支付流水状态，与渠道订单头 N:1（一笔订单可有多笔支付流水）。
 * 这是 INSTORE 与 O2O 1:1 详情模型的根本差异：承载"一笔订单多笔支付流水号"的 1:N 语义。
 * 唯一约束 (channel_code, trade_id) 为支付永久幂等键。
 * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#42-表结构ddl-新增-012-instore-channelsql}。</p>
 */
@TableName("sc_instore_payment_flow")
@Data
@Builder
public class InstorePaymentFlowDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String paymentFlowNo;
    private Long instoreOrderId;
    private Long orderId;
    private Integer flowSequence;
    private String channelCode;
    private String tradeId;
    private String userCode;
    private Long payAmount;
    private Integer orderType;
    private Short payStatus;
    private String outTradeNo;
    private String cashierUrl;
    private String cashierReference;
    private Long buyerPaidAmount;
    private LocalDateTime payTime;
    private String externalTransactionId;
    private String fundReservationNo;
    private Long supplierOrderId;
    private byte[] requestFingerprint;
    @Version
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
}
