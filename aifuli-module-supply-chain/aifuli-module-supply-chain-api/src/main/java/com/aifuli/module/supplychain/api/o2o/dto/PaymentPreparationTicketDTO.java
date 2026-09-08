package com.aifuli.module.supplychain.api.o2o.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Schema(description = "O2O 支付预下单 Ticket；抢占成功者获得不可变 Ticket")
@Data
public class PaymentPreparationTicketDTO implements Serializable {

    @Schema(description = "供应链总订单号")
    private String orderNo;

    @Schema(description = "拉起采购商收银台的稳定外部交易号；网络超时须按原 outTradeNo 查单，禁止生成新支付单号")
    private String outTradeNo;

    @Schema(description = "唯一调用标识；与 sc_o2o_operation.operation_no 同源")
    private String invocationId;

    @Schema(description = "收银台跳转描述；本期 stub 为配置化结果，收银台就绪后替换")
    private String launchDescriptor;

    @Schema(description = "抢占时记录的 Operation 乐观锁版本，用于 recordCashierOrder CAS")
    private Long expectedVersion;
}
