package com.aifuli.module.supplychain.border.instore.application;

import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreChannelOrderDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstorePaymentFlowDO;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreChannelOrderMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstorePaymentFlowMapper;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderTypes;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierChannelBindingDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierChannelBindingMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_IDENTITY_CONFLICT;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_ORDER_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_PAYMENT_FLOW_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_SUPPLIER_CHANNEL_BINDING_NOT_FOUND;

/**
 * 到店购 HOME 与订单详情只读校验服务。
 *
 * <p>不变量：companyCode 必须存在当前启用的渠道商户绑定；DETAIL 快照只来自
 * 总订单 → INSTORE 详情 → 唯一支付流水的固定加载链，归属冲突统一映射
 * {@code INSTORE_IDENTITY_CONFLICT}，缺失统一映射 029/031/032，订单状态不作为拒绝条件。
 * 本服务只做读取校验，可依赖 Mapper，但 Facade 不得绕过本服务直接依赖 Mapper。
 * 详见 {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#9-业务流程}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstoreEntryAccessService {

    private final SupplyChainOrderMapper orderMapper;
    private final InstoreChannelOrderMapper instoreOrderMapper;
    private final InstorePaymentFlowMapper paymentFlowMapper;
    private final SupplierChannelBindingMapper supplierChannelBindingMapper;

    /**
     * 校验 companyCode 在指定渠道存在当前启用的商户绑定。
     *
     * <p>校验失败发生在凭证签发之前，不创建任何凭证；companyCode 作为
     * {@code externalMerchantId} 参与绑定查询。</p>
     */
    public void requireEnabledCompany(String channelCode, String companyCode) {
        log.info("[INSTORE][entryAccess] requireEnabledCompany 入口 channelCode={}, companyCode={}",
                channelCode, companyCode);
        if (companyCode == null || companyCode.isBlank()) {
            throw exception(INSTORE_SUPPLIER_CHANNEL_BINDING_NOT_FOUND);
        }
        SupplierChannelBindingDO binding =
                supplierChannelBindingMapper.selectCurrentEnabled(channelCode, companyCode);
        if (binding == null) {
            log.warn("[INSTORE][entryAccess] 渠道商户绑定缺失或未启用 channelCode={}, companyCode={}",
                    channelCode, companyCode);
            throw exception(INSTORE_SUPPLIER_CHANNEL_BINDING_NOT_FOUND);
        }
    }

    /**
     * 按供应链采购单号加载订单详情只读快照，并校验归属。
     *
     * <p>固定加载顺序：总订单（orderType 必须 INSTORE）→ INSTORE 详情 → 唯一支付流水。
     * projectId/buyerId/buyerUserId 来自可信调用上下文，订单归属任一不匹配即拒绝；
     * 不校验订单状态，权威状态由详情页自行展示。</p>
     */
    public DetailEntrySnapshot requireDetail(String orderNo, Long projectId,
                                             Long buyerId, String buyerUserId) {
        log.info("[INSTORE][entryAccess] requireDetail 入口 orderNo={}, projectId={}, buyerId={}",
                orderNo, projectId, buyerId);
        SupplyChainOrderDO order = orderMapper.selectByOrderNo(orderNo);
        if (order == null
                || !SupplyChainOrderTypes.ORDER_TYPE_INSTORE.equals(order.getOrderType())) {
            log.warn("[INSTORE][entryAccess] 总订单不存在或非到店购订单 orderNo={}", orderNo);
            throw exception(INSTORE_ORDER_NOT_FOUND);
        }

        InstoreChannelOrderDO detail = instoreOrderMapper.selectByOrderId(order.getId());
        if (detail == null) {
            log.warn("[INSTORE][entryAccess] 到店购详情缺失 orderNo={}, orderId={}",
                    orderNo, order.getId());
            throw exception(INSTORE_ORDER_NOT_FOUND);
        }

        if (!Objects.equals(order.getBuyerId(), buyerId)
                || !Objects.equals(detail.getBuyerId(), buyerId)
                || !Objects.equals(detail.getProjectId(), projectId)
                || !Objects.equals(detail.getBuyerUserId(), buyerUserId)) {
            log.warn("[INSTORE][entryAccess] 订单归属与调用上下文不一致 orderNo={}, orderBuyerId={}, "
                            + "detailProjectId={}, detailBuyerUserId={}",
                    orderNo, order.getBuyerId(), detail.getProjectId(), detail.getBuyerUserId());
            throw exception(INSTORE_IDENTITY_CONFLICT);
        }

        InstorePaymentFlowDO flow = paymentFlowMapper.selectByInstoreOrder(detail.getId());
        if (flow == null || flow.getTradeId() == null || flow.getTradeId().isBlank()) {
            log.warn("[INSTORE][entryAccess] 支付流水缺失或 tradeId 为空 orderNo={}, instoreOrderId={}",
                    orderNo, detail.getId());
            throw exception(INSTORE_PAYMENT_FLOW_NOT_FOUND);
        }

        return new DetailEntrySnapshot(detail.getId(), detail.getChannelCode(),
                detail.getCompanyCode(), flow.getTradeId());
    }

    /**
     * 订单详情入口快照；channelCode/companyCode/tradeId 只取订单侧固化值，
     * 调用方不能提交或覆盖。
     */
    public record DetailEntrySnapshot(Long instoreOrderId, String channelCode,
                                      String companyCode, String tradeId) {
    }
}
