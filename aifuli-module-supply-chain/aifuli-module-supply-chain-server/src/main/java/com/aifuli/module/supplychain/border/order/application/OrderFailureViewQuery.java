package com.aifuli.module.supplychain.border.order.application;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.*;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.account.application.FundReservationService;
import com.aifuli.module.supplychain.tradecore.account.domain.FundReferenceType;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 管理详情的失败处置只读摘要，不读取卡密或执行供应商调用。
 * 资金仅经账户应用服务按履约请求冻结结算方取得；缺失即未知，不推断已经释放。
 * 结算方设计：docs/superpowers/specs/2026-10-14-project-settlement-buyer-design.md#3-数据与资金。
 * 设计：docs/superpowers/specs/2026-10-13-huasheng-error-classification-and-failure-closure-design.md#81-最小前端配套。
 */
@Service
@RequiredArgsConstructor
public class OrderFailureViewQuery {
    private final FulfillmentExceptionCaseMapper cases;
    private final FulfillmentRequestMapper requests;
    private final FundReservationService funds;
    private final RawStatusObservationMapper observations;

    public View read(BOrderDO order) {
        var request = requests.selectById(order.getFulfillmentRequestId());
        var reservation = request == null ? null : funds.findSnapshotByReference(
                request.fundsBuyerId(), FundReferenceType.CARD_ORDER.code(), request.getRequestNo());
        var caseRows = cases.selectList(new LambdaQueryWrapper<FulfillmentExceptionCaseDO>()
                .eq(FulfillmentExceptionCaseDO::getBOrderId, order.getId())
                .orderByDesc(FulfillmentExceptionCaseDO::getId));
        var expected = order.getExpectedSupplierSettlementAmount();
        var reported = order.getReportedSupplierSettlementAmount();
        var raw = observations.selectList(new LambdaQueryWrapper<RawStatusObservationDO>()
                .eq(RawStatusObservationDO::getBOrderId, order.getId())
                .orderByDesc(RawStatusObservationDO::getId));
        return new View(order.getTerminalReasonCode(), order.getTerminalReasonNote(),
                expected, reported, expected == null || reported == null ? null : reported - expected,
                caseRows.isEmpty() ? null : caseRows.getFirst().getCaseNo(), reservation,
                raw.isEmpty() ? null : raw.getFirst().getExternalStatus());
    }

    /** 金额为分；原始供应商状态只能来自正式观察，不能从本地履约状态猜测。 */
    public record View(String terminalReasonCode, String terminalReasonNote,
                       Long expectedSupplierSettlementAmount, Long reportedSupplierSettlementAmount,
                       Long supplierSettlementDifferenceAmount, String caseNo,
                       FundReservationService.ReservationSnapshot fundDisposition, String externalSupplierStatus) {}
}
