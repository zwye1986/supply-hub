package com.aifuli.channel.taobaoflash.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 渠道退款记录表 DO，对应 {@code tbsg_channel_refund}。
 *
 * <p>渠道服务在 ele.me /refund 验签通过、调中台 acceptRefund 成功后落本地退款记录，
 * 缓存 ele.me 退款回调地址（notifyUrl）与中台 refundCaseNo（outRefundNo）。中台 CHANNEL_NOTIFY
 * 回调 ele.me refundCallback 时按 channelRefundNo 查本表取 notifyUrl。该表属渠道服务自有库，
 * 与中台 DB 物理隔离。详见 docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.7。</p>
 */
@TableName("tbsg_channel_refund")
@Data
@Builder
public class TbsgChannelRefundDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** ele.me 渠道退款号，唯一 */
    private String channelRefundNo;

    /** ele.me 渠道交易号 */
    private String transactionId;

    /** 原支付中台 outTradeNo */
    private String outTradeNo;

    /** 中台退款 Case 号，回填 RefundResponse.outRefundNo */
    private String refundCaseNo;

    /** 退款金额（分） */
    private Integer refundAmount;

    /** ele.me 退款回调地址，refund 时从 RefundRequest.notifyUrl 取 */
    private String notifyUrl;

    /** 本地记录的退款状态：PROCESSING/SUCCESS/FAIL */
    private String refundStatus;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
