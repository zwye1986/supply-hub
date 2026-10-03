package com.aifuli.module.supplychain.api.fulfillment.dto;

import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 项目标准订单受理封闭响应。
 *
 * <p>下单响应复用 CommonResult 外层，data 提供稳定受理回执号、采购商订单号、回执状态、回执版本、
 * 订单号、订单状态、拒绝码、受理时间与幂等重放标记；未建单字段显式为 null。
 * 绝不暴露供应商编码/名称、内部履约请求号、内部备注或数字主键。
 * 详见 {@code docs/superpowers/specs/2026-09-30-project-signed-standard-order-http-design.md#51-响应契约与状态隔离}。</p>
 */
@Data
public class ProjectStandardOrderCreateRespDTO {

    /** 稳定受理回执号，对应受理回执稳定业务编号 */
    private String requestId;

    /** 采购商侧C端订单号，与buyer_id组成永久受理键 */
    private String buyerOrderNo;

    /** 回执状态：0 PROCESSING处理中，10 ACCEPTED已受理，20 REJECTED_RETRYABLE条件修复后可重试，30 REJECTED_FINAL最终拒绝 */
    private FulfillmentReceiptStatus receiptStatus;

    /** 回执状态及条件重试CAS版本号 */
    private Long receiptVersion;

    /** 供应链总订单稳定业务编号，未建单时为 null */
    private String orderNo;

    /** 公共主状态：0已创建，10处理中，20部分成功，30成功，40失败，50已取消，90人工处理，未建单时为 null */
    private SupplyChainOrderStatus orderStatus;

    /** 拒绝原因稳定错误码，处理中或已受理时为空 */
    private String rejectionCode;

    /** 是否为永久业务键的幂等重放 */
    private Boolean idempotentReplay;

    /** 受理成功时间，Asia/Shanghai，未受理成功时为 null */
    private OffsetDateTime acceptedAt;
}
