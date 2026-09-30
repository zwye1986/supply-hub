package com.aifuli.framework.jdvop.core.order;

import com.aifuli.framework.jdvop.config.JdVopProperties;
import com.aifuli.framework.jdvop.core.client.JdVopClient;
import com.aifuli.framework.jdvop.core.exception.JdVopBusinessException;
import com.aifuli.framework.jdvop.core.exception.JdVopException;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 京东 VOP 订单客户端默认实现。
 */
@Slf4j
public class DefaultJdVopOrderClient implements JdVopOrderClient {

    /** 京东提交订单接口方法名。 */
    private static final String SUBMIT_ORDER_METHOD = "jingdong.vop.order.submitOrder";
    /** 京东查询订单接口方法名。 */
    private static final String QUERY_ORDER_METHOD = "jingdong.vop.order.queryOrderDetail";
    /** 京东查询配送信息接口方法名。 */
    private static final String QUERY_DELIVERY_METHOD = "jingdong.vop.order.queryDeliveryInfo";
    /** 京东取消订单接口方法名。 */
    private static final String CANCEL_ORDER_METHOD = "jingdong.vop.order.cancelOrder";
    /** 京东业务成功码。 */
    private static final Set<String> SUCCESS_RESULT_CODES = Set.of("0", "0000");
    /** 直接进入京东生产流程，不使用待确认预占模式。 */
    private static final int SUBMIT_STATE_DIRECT = 1;
    /** 京东预存款支付方式。 */
    private static final int PAYMENT_TYPE_PREPAID = 4;

    private final JdVopProperties properties;
    private final JdVopClient jdVopClient;

    public DefaultJdVopOrderClient(JdVopProperties properties, JdVopClient jdVopClient) {
        this.properties = properties;
        this.jdVopClient = jdVopClient;
    }

    @Override
    public JdOrderResult submitOrder(JdOrderSubmitRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("京东提交订单请求不能为空");
        }
        Map<String, Object> submitOrderParams = buildSubmitOrderParams(request);
        SubmitOrderResponse response = jdVopClient.execute(SUBMIT_ORDER_METHOD,
                Map.of("submitOrderOpenReq", submitOrderParams), SubmitOrderResponse.class);
        SubmitOrderRpcResult rpcResult = response == null ? null : response.vopOrderRpcResult();
        SubmitOrderResult submitResult = rpcResult == null ? null : rpcResult.result();
        // INFO 记录下单业务码和消息，避免为排查京东业务拒绝临时调高全局日志级别。
        log.info("京东提交订单响应：thirdOrderId={}, success={}, resultCode={}, resultMessage={}, jdOrderId={}",
                request.thirdOrderId(), rpcResult == null ? null : rpcResult.success(),
                rpcResult == null ? null : rpcResult.resultCode(),
                rpcResult == null ? null : rpcResult.resultMessage(),
                submitResult == null ? null : submitResult.jdOrderId());
        validateRpcResult(rpcResult == null ? null : rpcResult.success(),
                rpcResult == null ? null : rpcResult.resultCode(),
                rpcResult == null ? null : rpcResult.resultMessage(), "京东提交订单");
        SubmitOrderResult result = submitResult;
        if (result == null || result.jdOrderId() == null || result.jdOrderId() <= 0) {
            throw new JdVopException("京东提交订单未返回有效订单号");
        }
        if (StringUtils.hasText(result.thirdOrderId())
                && !request.thirdOrderId().equals(result.thirdOrderId())) {
            throw new JdVopException("京东提交订单返回的第三方订单号不匹配");
        }
        validateSubmittedSkus(request.skuInfoList(), result.skuInfoList());
        PriceResult orderPrice = result.orderPrice();
        return new JdOrderResult(result.jdOrderId(), result.thirdOrderId(), result.parentType(),
                orderPrice == null ? null : orderPrice.orderTotalPrice(),
                orderPrice == null ? null : orderPrice.orderTotalFreight(),
                convertSubmitSkus(result.skuInfoList()));
    }

    @Override
    public List<JdOrderDetail> queryOrderByThirdOrderId(String thirdOrderId) {
        if (!StringUtils.hasText(thirdOrderId)) {
            throw new IllegalArgumentException("京东第三方订单号不能为空");
        }
        String normalizedThirdOrderId = thirdOrderId.trim();
        List<JdOrderDetail> details = queryOrder(Map.of("thirdOrderId", normalizedThirdOrderId));
        if (details.stream().anyMatch(detail -> StringUtils.hasText(detail.thirdOrderId())
                && !normalizedThirdOrderId.equals(detail.thirdOrderId()))) {
            throw new JdVopException("京东查询订单返回的第三方订单号不匹配");
        }
        return details;
    }

    @Override
    public List<JdOrderDetail> queryOrderByJdOrderId(Long jdOrderId) {
        if (jdOrderId == null || jdOrderId <= 0) {
            throw new IllegalArgumentException("京东订单号必须大于 0");
        }
        return queryOrder(Map.of("jdOrderId", jdOrderId));
    }

    @Override
    public JdDeliveryInfo queryDeliveryInfo(Long jdOrderId) {
        if (jdOrderId == null || jdOrderId <= 0) {
            throw new IllegalArgumentException("京东订单号必须大于 0");
        }
        QueryDeliveryResponse response = jdVopClient.execute(
                QUERY_DELIVERY_METHOD, Map.of("jdOrderId", jdOrderId), QueryDeliveryResponse.class);
        QueryDeliveryRpcResult rpcResult = response == null ? null : response.openRpcResult();
        validateRpcResult(rpcResult == null ? null : rpcResult.success(),
                rpcResult == null ? null : rpcResult.resultCode(),
                rpcResult == null ? null : rpcResult.resultMessage(), "京东查询配送信息");
        QueryDeliveryResult result = rpcResult.result();
        if (result == null) {
            return new JdDeliveryInfo(jdOrderId, List.of(), List.of());
        }
        if (result.jdOrderId() != null && !jdOrderId.equals(result.jdOrderId())) {
            throw new JdVopException("京东查询配送信息返回的订单号不匹配");
        }
        List<JdDeliveryInfo.PackageInfo> packages = result.logisticInfoList() == null ? List.of()
                : result.logisticInfoList().stream()
                .filter(Objects::nonNull)
                .map(item -> new JdDeliveryInfo.PackageInfo(item.jdOrderId(), item.parentJdOrderId(),
                        item.deliveryOrderId(), item.deliveryCarrier()))
                .toList();
        List<JdDeliveryInfo.TrackInfo> tracks = result.trackInfoList() == null ? List.of()
                : result.trackInfoList().stream()
                .filter(Objects::nonNull)
                .map(item -> new JdDeliveryInfo.TrackInfo(toLocalDateTime(item.trackMsgTime()),
                        item.trackContent(), item.trackOperator()))
                .toList();
        return new JdDeliveryInfo(jdOrderId, packages, tracks);
    }

    @Override
    public boolean cancelOrder(Long jdOrderId, String cancelReason) {
        if (jdOrderId == null || jdOrderId <= 0) {
            throw new IllegalArgumentException("京东订单号必须大于 0");
        }
        if (!StringUtils.hasText(cancelReason)) {
            throw new IllegalArgumentException("京东取消原因不能为空");
        }
        CancelOrderResponse response = jdVopClient.execute(CANCEL_ORDER_METHOD,
                Map.of("jdOrderId", jdOrderId, "cancelReason", cancelReason.trim()),
                CancelOrderResponse.class);
        BooleanRpcResult rpcResult = response == null ? null : response.openRpcResult();
        validateRpcResult(rpcResult == null ? null : rpcResult.success(),
                rpcResult == null ? null : rpcResult.resultCode(),
                rpcResult == null ? null : rpcResult.resultMessage(), "京东取消订单");
        if (!Boolean.TRUE.equals(rpcResult.result())) {
            throw new JdVopBusinessException(rpcResult.resultCode(), "京东取消订单返回失败");
        }
        return true;
    }

    /** 构造京东提交订单业务参数，避免把商城内部字段透传到供应商。 */
    private Map<String, Object> buildSubmitOrderParams(JdOrderSubmitRequest request) {
        Map<String, Object> submitOrderParams = new LinkedHashMap<>();
        submitOrderParams.put("thirdOrderId", request.thirdOrderId());
        submitOrderParams.put("submitStateType", SUBMIT_STATE_DIRECT);

        List<Map<String, Object>> skuInfoList = request.skuInfoList().stream().map(sku -> {
            Map<String, Object> skuInfo = new LinkedHashMap<>();
            skuInfo.put("skuId", sku.skuId());
            skuInfo.put("skuNum", sku.skuNum());
            skuInfo.put("skuNeedGift", false);
            return skuInfo;
        }).toList();
        submitOrderParams.put("skuInfoList", skuInfoList);
        submitOrderParams.put("paymentInfo", Map.of("paymentType", PAYMENT_TYPE_PREPAID));

        JdOrderSubmitRequest.ConsigneeInfo consignee = request.consigneeInfo();
        Map<String, Object> consigneeInfo = new LinkedHashMap<>();
        consigneeInfo.put("consigneeName", consignee.name());
        consigneeInfo.put("consigneeMobile", consignee.mobile());
        consigneeInfo.put("consigneeProvinceId", consignee.provinceId());
        consigneeInfo.put("consigneeCityId", consignee.cityId());
        consigneeInfo.put("consigneeCountyId", consignee.countyId());
        consigneeInfo.put("consigneeTownId", consignee.townId() == null ? 0L : consignee.townId());
        consigneeInfo.put("consigneeAddress", consignee.address());
        submitOrderParams.put("consigneeInfo", consigneeInfo);

        Map<String, Object> invoiceInfo = buildInvoiceInfo();
        if (invoiceInfo != null) {
            submitOrderParams.put("invoiceInfo", invoiceInfo);
        }
        return submitOrderParams;
    }

    /** 按配置构造企业发票参数，禁止在代码中固化企业和银行敏感信息。 */
    private Map<String, Object> buildInvoiceInfo() {
        JdVopProperties.Order.Invoice invoice = properties.getOrder().getInvoice();
        if (!invoice.isEnabled()) {
            return null;
        }
        invoice.validateRequiredFields();
        Map<String, Object> invoiceInfo = new LinkedHashMap<>();
        invoiceInfo.put("invoiceContentType", invoice.getContentType());
        invoiceInfo.put("invoiceType", invoice.getType());
        invoiceInfo.put("invoiceSelectedTitle", invoice.getSelectedTitle());
        invoiceInfo.put("invoicePutType", invoice.getPutType());
        invoiceInfo.put("invoiceCompanyName", invoice.getCompanyName());
        invoiceInfo.put("invoiceName", invoice.getName());
        invoiceInfo.put("invoicePhone", invoice.getPhone());
        invoiceInfo.put("invoiceRegPhone", invoice.getRegPhone());
        invoiceInfo.put("invoiceRegCompanyName", invoice.getRegCompanyName());
        invoiceInfo.put("invoiceRegCode", invoice.getRegCode());
        invoiceInfo.put("invoiceAddress", invoice.getAddress());
        invoiceInfo.put("invoiceRegAddress", invoice.getRegAddress());
        invoiceInfo.put("invoiceRegBank", invoice.getRegBank());
        invoiceInfo.put("invoiceRegBankAccount", invoice.getRegBankAccount());
        invoiceInfo.put("invoiceProvinceId", invoice.getProvinceId());
        invoiceInfo.put("invoiceCityId", invoice.getCityId());
        invoiceInfo.put("invoiceCountyId", invoice.getCountyId());
        invoiceInfo.put("invoiceTownId", invoice.getTownId());
        return invoiceInfo;
    }

    /** 调用京东订单详情接口并转换为稳定的公开 DTO。 */
    private List<JdOrderDetail> queryOrder(Map<String, Object> queryParams) {
        QueryOrderResponse response = jdVopClient.execute(
                QUERY_ORDER_METHOD, queryParams, QueryOrderResponse.class);
        QueryOrderRpcResult rpcResult = response == null ? null : response.openRpcResult();
        validateRpcResult(rpcResult == null ? null : rpcResult.success(),
                rpcResult == null ? null : rpcResult.resultCode(),
                rpcResult == null ? null : rpcResult.resultMessage(), "京东查询订单");
        if (rpcResult.result() == null || rpcResult.result().isEmpty()) {
            return List.of();
        }
        List<JdOrderDetail> details = new ArrayList<>(rpcResult.result().size());
        for (QueryOrderResult result : rpcResult.result()) {
            if (result == null || result.jdOrderId() == null || result.jdOrderId() <= 0) {
                throw new JdVopException("京东查询订单返回了无效订单号");
            }
            OrderStateResult state = result.orderState();
            List<JdOrderDetail.SkuInfo> skuInfoList = result.skuInfoList() == null ? List.of()
                    : result.skuInfoList().stream().filter(Objects::nonNull)
                    .map(sku -> new JdOrderDetail.SkuInfo(sku.skuId(), sku.skuNum(), sku.type())).toList();
            LocalDateTime deliveryTime = result.orderDeliveryInfo() == null ? null
                    : toLocalDateTime(result.orderDeliveryInfo().deliveryOutTime());
            details.add(new JdOrderDetail(result.jdOrderId(), result.thirdOrderId(), result.parentType(),
                    result.parentJdOrderId(), result.childJdOrderIdList(), skuInfoList, deliveryTime,
                    state == null ? null
                    : new JdOrderDetail.OrderState(state.confirmState(), state.cancelOrderState(),
                    state.deliveryState(), state.jdOrderState())));
        }
        return List.copyOf(details);
    }

    /** 将京东 SDK 的时间转换为业务本地时间。 */
    private static LocalDateTime toLocalDateTime(Date date) {
        return date == null ? null : LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }

    /** 校验京东双层业务响应，明确业务拒绝使用独立异常类型。 */
    private static void validateRpcResult(Boolean success, String resultCode, String resultMessage,
                                          String operationName) {
        if (success == null) {
            throw new JdVopException(operationName + "缺少 openRpcResult");
        }
        if (!Boolean.TRUE.equals(success) || !SUCCESS_RESULT_CODES.contains(resultCode)) {
            String message = StringUtils.hasText(resultMessage) ? resultMessage : "未知错误";
            throw new JdVopBusinessException(resultCode, operationName + "失败：" + message);
        }
    }

    /** 校验下单响应至少完整覆盖请求的 SKU 和数量。 */
    private static void validateSubmittedSkus(List<JdOrderSubmitRequest.SkuInfo> requestedSkus,
                                               List<SubmitSkuResult> returnedSkus) {
        if (returnedSkus == null || returnedSkus.isEmpty()) {
            throw new JdVopException("京东提交订单未返回商品明细");
        }
        Map<Long, Integer> returnedSkuCount = new LinkedHashMap<>();
        for (SubmitSkuResult returnedSku : returnedSkus) {
            if (returnedSku == null || returnedSku.skuId() == null || returnedSku.skuNum() == null
                    || returnedSkuCount.put(returnedSku.skuId(), returnedSku.skuNum()) != null) {
                throw new JdVopException("京东提交订单返回了无效或重复的商品明细");
            }
        }
        for (JdOrderSubmitRequest.SkuInfo requestedSku : requestedSkus) {
            if (!requestedSku.skuNum().equals(returnedSkuCount.get(requestedSku.skuId()))) {
                throw new JdVopException("京东提交订单返回的商品数量不匹配");
            }
        }
        Set<Long> requestedSkuIds = requestedSkus.stream()
                .map(JdOrderSubmitRequest.SkuInfo::skuId).collect(java.util.stream.Collectors.toSet());
        if (!requestedSkuIds.equals(returnedSkuCount.keySet())) {
            throw new JdVopException("京东提交订单返回了非预期商品");
        }
    }

    /** 转换京东下单商品结果。 */
    private static List<JdOrderResult.SkuInfo> convertSubmitSkus(List<SubmitSkuResult> skuInfoList) {
        return skuInfoList.stream().map(sku ->
                new JdOrderResult.SkuInfo(sku.skuId(), sku.skuNum(), sku.skuPrice())).toList();
    }

    private record SubmitOrderResponse(
            @JsonProperty("vopOrderRpcResult") SubmitOrderRpcResult vopOrderRpcResult) {
    }

    private record SubmitOrderRpcResult(
            Boolean success,
            String resultCode,
            String resultMessage,
            SubmitOrderResult result) {
    }

    private record SubmitOrderResult(
            Long jdOrderId,
            String thirdOrderId,
            Integer parentType,
            PriceResult orderPrice,
            List<SubmitSkuResult> skuInfoList) {
    }

    private record PriceResult(
            BigDecimal orderTotalPrice,
            BigDecimal orderTotalFreight) {
    }

    private record SubmitSkuResult(
            Long skuId,
            Integer skuNum,
            BigDecimal skuPrice) {
    }

    private record QueryOrderResponse(
            @JsonProperty("openRpcResult") QueryOrderRpcResult openRpcResult) {
    }

    private record QueryOrderRpcResult(
            Boolean success,
            String resultCode,
            String resultMessage,
            List<QueryOrderResult> result) {
    }

    private record QueryOrderResult(
            Long jdOrderId,
            String thirdOrderId,
            Integer parentType,
            Long parentJdOrderId,
            List<Long> childJdOrderIdList,
            List<QueryOrderSkuResult> skuInfoList,
            QueryOrderDeliveryResult orderDeliveryInfo,
            OrderStateResult orderState) {
    }

    private record QueryOrderSkuResult(
            Long skuId,
            Integer skuNum,
            Integer type) {
    }

    private record QueryOrderDeliveryResult(
            Date deliveryOutTime) {
    }

    private record OrderStateResult(
            Integer confirmState,
            Integer cancelOrderState,
            Integer deliveryState,
            Integer jdOrderState) {
    }

    private record QueryDeliveryResponse(
            @JsonProperty("openRpcResult") QueryDeliveryRpcResult openRpcResult) {
    }

    private record QueryDeliveryRpcResult(
            Boolean success,
            String resultCode,
            String resultMessage,
            QueryDeliveryResult result) {
    }

    private record QueryDeliveryResult(
            String thirdOrderId,
            Long jdOrderId,
            List<QueryLogisticResult> logisticInfoList,
            List<QueryTrackResult> trackInfoList) {
    }

    private record QueryLogisticResult(
            String deliveryCarrier,
            Long jdOrderId,
            String deliveryOrderId,
            Long parentJdOrderId) {
    }

    private record QueryTrackResult(
            Date trackMsgTime,
            String trackContent,
            String trackOperator) {
    }

    private record CancelOrderResponse(
            @JsonProperty("openRpcResult") BooleanRpcResult openRpcResult) {
    }

    private record BooleanRpcResult(
            Boolean success,
            String resultCode,
            String resultMessage,
            Boolean result) {
    }

}
