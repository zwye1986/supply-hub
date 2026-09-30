package com.aifuli.module.supplychain.border.instore.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.border.instore.application.InstoreEntryAccessService.DetailEntrySnapshot;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreChannelOrderDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstorePaymentFlowDO;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreChannelOrderMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstorePaymentFlowMapper;
import com.aifuli.module.supplychain.border.instore.domain.InstoreOrderStatus;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderTypes;
import com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierChannelBindingDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierChannelBindingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 到店购 HOME 与订单详情只读校验单元测试：companyCode 必须存在启用渠道商户绑定；
 * DETAIL 快照只来自总订单、INSTORE 详情与唯一支付流水，归属冲突与缺失映射稳定错误码，
 * 订单状态不作为拒绝条件。
 *
 * <p>设计见
 * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#9-业务流程}。</p>
 */
@ExtendWith(MockitoExtension.class)
class InstoreEntryAccessServiceTest {

    private static final String ORDER_NO = "SCO_1";
    private static final Long ORDER_ID = 71L;
    private static final Long INSTORE_ORDER_ID = 77L;
    private static final Long PROJECT_ID = 101L;
    private static final Long BUYER_ID = 1001L;
    private static final String BUYER_USER_ID = "opaque-user";
    private static final String CHANNEL_CODE = "RT_MART";
    private static final String COMPANY_CODE = "COMPANY_A";
    private static final String TRADE_ID = "TRADE_1";

    @Mock
    private SupplyChainOrderMapper orderMapper;
    @Mock
    private InstoreChannelOrderMapper instoreOrderMapper;
    @Mock
    private InstorePaymentFlowMapper paymentFlowMapper;
    @Mock
    private SupplierChannelBindingMapper supplierChannelBindingMapper;

    private InstoreEntryAccessService service;

    @BeforeEach
    void setUp() {
        service = new InstoreEntryAccessService(
                orderMapper, instoreOrderMapper, paymentFlowMapper, supplierChannelBindingMapper);
    }

    @Test
    void requireEnabledCompanyPassesWithEnabledBinding() {
        when(supplierChannelBindingMapper.selectCurrentEnabled(CHANNEL_CODE, COMPANY_CODE))
                .thenReturn(SupplierChannelBindingDO.builder().id(55L).build());

        assertThatCode(() -> service.requireEnabledCompany(CHANNEL_CODE, COMPANY_CODE))
                .doesNotThrowAnyException();
        verify(supplierChannelBindingMapper).selectCurrentEnabled(CHANNEL_CODE, COMPANY_CODE);
    }

    @Test
    void requireEnabledCompanyRejectsMissingBinding() {
        when(supplierChannelBindingMapper.selectCurrentEnabled(CHANNEL_CODE, COMPANY_CODE))
                .thenReturn(null);

        assertThatThrownBy(() -> service.requireEnabledCompany(CHANNEL_CODE, COMPANY_CODE))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants
                        .INSTORE_SUPPLIER_CHANNEL_BINDING_NOT_FOUND.getCode());
    }

    @Test
    void requireEnabledCompanyRejectsBlankCompanyCodeWithoutDatabaseAccess() {
        assertThatThrownBy(() -> service.requireEnabledCompany(CHANNEL_CODE, " "))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants
                        .INSTORE_SUPPLIER_CHANNEL_BINDING_NOT_FOUND.getCode());

        verifyNoInteractions(supplierChannelBindingMapper);
    }

    @Test
    void requireDetailReturnsSnapshotRegardlessOfOrderStatus() {
        SupplyChainOrderDO order = instoreOrder();
        order.setOrderStatus(InstoreOrderStatus.CANCELLED.getCode());
        when(orderMapper.selectByOrderNo(ORDER_NO)).thenReturn(order);
        when(instoreOrderMapper.selectByOrderId(ORDER_ID)).thenReturn(instoreDetail());
        when(paymentFlowMapper.selectByInstoreOrder(INSTORE_ORDER_ID)).thenReturn(paymentFlow(TRADE_ID));

        DetailEntrySnapshot detail = service.requireDetail(ORDER_NO, PROJECT_ID, BUYER_ID, BUYER_USER_ID);

        assertThat(detail).isEqualTo(new DetailEntrySnapshot(
                INSTORE_ORDER_ID, CHANNEL_CODE, COMPANY_CODE, TRADE_ID));
    }

    @Test
    void requireDetailRejectsMissingOrder() {
        when(orderMapper.selectByOrderNo(ORDER_NO)).thenReturn(null);

        assertThatThrownBy(() -> service.requireDetail(ORDER_NO, PROJECT_ID, BUYER_ID, BUYER_USER_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_ORDER_NOT_FOUND.getCode());
        verifyNoInteractions(instoreOrderMapper, paymentFlowMapper);
    }

    @Test
    void requireDetailRejectsNonInstoreOrder() {
        SupplyChainOrderDO cardOrder = instoreOrder();
        cardOrder.setOrderType(SupplyChainOrderTypes.ORDER_TYPE_CARD);
        when(orderMapper.selectByOrderNo(ORDER_NO)).thenReturn(cardOrder);

        assertThatThrownBy(() -> service.requireDetail(ORDER_NO, PROJECT_ID, BUYER_ID, BUYER_USER_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_ORDER_NOT_FOUND.getCode());
        verifyNoInteractions(instoreOrderMapper, paymentFlowMapper);
    }

    @Test
    void requireDetailRejectsMissingInstoreDetail() {
        when(orderMapper.selectByOrderNo(ORDER_NO)).thenReturn(instoreOrder());
        when(instoreOrderMapper.selectByOrderId(ORDER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.requireDetail(ORDER_NO, PROJECT_ID, BUYER_ID, BUYER_USER_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_ORDER_NOT_FOUND.getCode());
        verifyNoInteractions(paymentFlowMapper);
    }

    @Test
    void requireDetailRejectsProjectMismatch() {
        when(orderMapper.selectByOrderNo(ORDER_NO)).thenReturn(instoreOrder());
        InstoreChannelOrderDO otherProjectDetail = instoreDetail();
        otherProjectDetail.setProjectId(202L);
        when(instoreOrderMapper.selectByOrderId(ORDER_ID)).thenReturn(otherProjectDetail);

        assertThatThrownBy(() -> service.requireDetail(ORDER_NO, PROJECT_ID, BUYER_ID, BUYER_USER_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_IDENTITY_CONFLICT.getCode());
        verifyNoInteractions(paymentFlowMapper);
    }

    @Test
    void requireDetailRejectsBuyerMismatch() {
        when(orderMapper.selectByOrderNo(ORDER_NO)).thenReturn(instoreOrder());
        InstoreChannelOrderDO otherBuyerDetail = instoreDetail();
        otherBuyerDetail.setBuyerId(9999L);
        when(instoreOrderMapper.selectByOrderId(ORDER_ID)).thenReturn(otherBuyerDetail);

        assertThatThrownBy(() -> service.requireDetail(ORDER_NO, PROJECT_ID, BUYER_ID, BUYER_USER_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_IDENTITY_CONFLICT.getCode());
        verifyNoInteractions(paymentFlowMapper);
    }

    @Test
    void requireDetailRejectsOrderBuyerMismatch() {
        SupplyChainOrderDO otherBuyerOrder = instoreOrder();
        otherBuyerOrder.setBuyerId(9999L);
        when(orderMapper.selectByOrderNo(ORDER_NO)).thenReturn(otherBuyerOrder);
        when(instoreOrderMapper.selectByOrderId(ORDER_ID)).thenReturn(instoreDetail());

        assertThatThrownBy(() -> service.requireDetail(ORDER_NO, PROJECT_ID, BUYER_ID, BUYER_USER_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_IDENTITY_CONFLICT.getCode());
        verifyNoInteractions(paymentFlowMapper);
    }

    @Test
    void requireDetailRejectsBuyerUserMismatch() {
        when(orderMapper.selectByOrderNo(ORDER_NO)).thenReturn(instoreOrder());
        InstoreChannelOrderDO otherUserDetail = instoreDetail();
        otherUserDetail.setBuyerUserId("opaque-other-user");
        when(instoreOrderMapper.selectByOrderId(ORDER_ID)).thenReturn(otherUserDetail);

        assertThatThrownBy(() -> service.requireDetail(ORDER_NO, PROJECT_ID, BUYER_ID, BUYER_USER_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_IDENTITY_CONFLICT.getCode());
        verifyNoInteractions(paymentFlowMapper);
    }

    @Test
    void requireDetailRejectsMissingPaymentFlow() {
        when(orderMapper.selectByOrderNo(ORDER_NO)).thenReturn(instoreOrder());
        when(instoreOrderMapper.selectByOrderId(ORDER_ID)).thenReturn(instoreDetail());
        when(paymentFlowMapper.selectByInstoreOrder(INSTORE_ORDER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.requireDetail(ORDER_NO, PROJECT_ID, BUYER_ID, BUYER_USER_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_PAYMENT_FLOW_NOT_FOUND.getCode());
    }

    @Test
    void requireDetailRejectsBlankTradeId() {
        when(orderMapper.selectByOrderNo(ORDER_NO)).thenReturn(instoreOrder());
        when(instoreOrderMapper.selectByOrderId(ORDER_ID)).thenReturn(instoreDetail());
        when(paymentFlowMapper.selectByInstoreOrder(INSTORE_ORDER_ID)).thenReturn(paymentFlow(" "));

        assertThatThrownBy(() -> service.requireDetail(ORDER_NO, PROJECT_ID, BUYER_ID, BUYER_USER_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_PAYMENT_FLOW_NOT_FOUND.getCode());
    }

    private SupplyChainOrderDO instoreOrder() {
        return SupplyChainOrderDO.builder()
                .id(ORDER_ID)
                .orderNo(ORDER_NO)
                .orderType(SupplyChainOrderTypes.ORDER_TYPE_INSTORE)
                .buyerId(BUYER_ID)
                .buyerUserId(BUYER_USER_ID)
                .build();
    }

    private InstoreChannelOrderDO instoreDetail() {
        return InstoreChannelOrderDO.builder()
                .id(INSTORE_ORDER_ID)
                .orderId(ORDER_ID)
                .buyerId(BUYER_ID)
                .projectId(PROJECT_ID)
                .buyerUserId(BUYER_USER_ID)
                .channelCode(CHANNEL_CODE)
                .companyCode(COMPANY_CODE)
                .build();
    }

    private InstorePaymentFlowDO paymentFlow(String tradeId) {
        return InstorePaymentFlowDO.builder()
                .id(88L)
                .instoreOrderId(INSTORE_ORDER_ID)
                .channelCode(CHANNEL_CODE)
                .tradeId(tradeId)
                .build();
    }
}
