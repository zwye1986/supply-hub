package com.aifuli.framework.jdvop.core.product;

import com.aifuli.framework.jdvop.core.client.JdVopClient;
import com.aifuli.framework.jdvop.core.exception.JdVopException;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 京东 VOP 商品客户端默认实现。
 */
public class DefaultJdVopProductClient implements JdVopProductClient {

    /**
     * 对应旧项目 {@code VopGoodsGetSkusAllSaleStateRequest#getApiMethod()}。
     */
    private static final String CHECK_PRODUCT_METHOD = "jingdong.vop.goods.getSkusAllSaleState";
    /**
     * 京东业务成功码，兼容不同 VOP 网关返回的 0 和 0000。
     */
    private static final Set<String> SUCCESS_RESULT_CODES = Set.of("0", "0000");

    private final JdVopClient jdVopClient;

    public DefaultJdVopProductClient(JdVopClient jdVopClient) {
        this.jdVopClient = jdVopClient;
    }

    @Override
    public List<JdSkuSaleState> checkProduct(JdProductCheckRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("京东商品可采购校验请求不能为空");
        }
        Map<String, Object> applicationParams = Map.of("getStockByIdGoodsReq", request);
        ProductCheckResponse response = jdVopClient.execute(
                CHECK_PRODUCT_METHOD, applicationParams, ProductCheckResponse.class);
        if (response == null) {
            throw new JdVopException("京东商品可采购校验返回为空");
        }

        ProductCheckRpcResult rpcResult = response.openRpcResult();
        if (rpcResult == null) {
            throw new JdVopException("京东商品可采购校验缺少 openRpcResult");
        }
        if (!Boolean.TRUE.equals(rpcResult.success())
                || !SUCCESS_RESULT_CODES.contains(rpcResult.resultCode())) {
            throw new JdVopException(rpcResult.resultCode(),
                    "京东商品可采购校验失败：" + defaultMessage(rpcResult.resultMessage()));
        }

        List<JdSkuSaleState> saleStates = rpcResult.result();
        if (saleStates == null || saleStates.isEmpty()) {
            throw new JdVopException(rpcResult.resultCode(), "京东商品可采购校验未返回 SKU 校验结果");
        }
        validateResultCompleteness(request, saleStates);
        return List.copyOf(saleStates);
    }

    /**
     * 校验京东返回结果是否完整覆盖本次请求的所有 SKU。
     */
    private static void validateResultCompleteness(JdProductCheckRequest request, List<JdSkuSaleState> saleStates) {
        Set<Long> requestedSkuIds = new LinkedHashSet<>();
        for (JdSkuQuantity skuQuantity : request.skuNumInfoList()) {
            requestedSkuIds.add(skuQuantity.skuId());
        }

        Set<Long> returnedSkuIds = new LinkedHashSet<>();
        for (JdSkuSaleState saleState : saleStates) {
            if (saleState == null || saleState.skuId() == null || saleState.canPurchase() == null) {
                throw new JdVopException("京东商品可采购校验返回了无效的 SKU 结果");
            }
            if (!returnedSkuIds.add(saleState.skuId())) {
                throw new JdVopException(
                        "京东商品可采购校验返回了重复的 SKU：" + saleState.skuId());
            }
        }

        if (!returnedSkuIds.equals(requestedSkuIds)) {
            Set<Long> missingSkuIds = new LinkedHashSet<>(requestedSkuIds);
            missingSkuIds.removeAll(returnedSkuIds);
            Set<Long> unexpectedSkuIds = new LinkedHashSet<>(returnedSkuIds);
            unexpectedSkuIds.removeAll(requestedSkuIds);
            throw new JdVopException("京东商品可采购校验结果不完整，缺失 SKU："
                    + missingSkuIds + "，非预期 SKU：" + unexpectedSkuIds);
        }
    }

    private static String defaultMessage(String message) {
        return message == null || message.isBlank() ? "未知错误" : message;
    }

    private record ProductCheckResponse(
            @JsonProperty("openRpcResult") ProductCheckRpcResult openRpcResult) {
    }

    private record ProductCheckRpcResult(
            @JsonProperty("success") Boolean success,
            @JsonProperty("resultCode") String resultCode,
            @JsonProperty("resultMessage") String resultMessage,
            @JsonProperty("result") List<JdSkuSaleState> result) {
    }

}
