package com.aifuli.channel.taobaoflash.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 渠道交易注册表 DO，对应 {@code tbsg_channel_transaction}。
 *
 * <p>渠道服务在 ele.me /pay 验签通过、调中台 preparePayment 成功后落本地交易记录，
 * 缓存 ele.me 平台回调地址（notifyUrl）等协议字段。中台 CHANNEL_NOTIFY 不携带 notifyUrl，
 * 回调 ele.me payCallback 时按 transactionId 查本表取 notifyUrl。
 * 该表属渠道服务自有库，与中台 DB 物理隔离。详见
 * docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.7。</p>
 */
@TableName("tbsg_channel_transaction")
@Data
@Builder
public class TbsgChannelTransactionDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** ele.me 渠道交易号，唯一 */
    private String transactionId;

    /** 中台返回的稳定外部交易号 */
    private String outTradeNo;

    /** 中台总订单号 */
    private String orderNo;

    /** 支付金额（分） */
    private Integer payAmount;

    /** ele.me 用户标识（uid），对应中台 channelUserId */
    private String channelUserId;

    /** ele.me 平台回调地址，pay 时从 PayRequest.notifyUrl 取 */
    private String notifyUrl;

    /** 商品标题 */
    private String subject;

    /** 本地记录的支付状态：NOTPAY/PAID/CLOSED */
    private String payStatus;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
