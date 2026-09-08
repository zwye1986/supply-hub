package com.aifuli.framework.jdvop.core.order;

import com.aifuli.framework.jdvop.config.JdVopProperties;
import com.aifuli.framework.jdvop.core.client.JdVopClient;
import com.aifuli.framework.jdvop.core.exception.JdVopException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 京东 VOP 订单请求契约测试。
 */
class DefaultJdVopOrderClientTest {

    /** 验证下单请求字段和双层响应转换。 */
    @Test
    void submitOrder_buildsSdkCompatiblePayload() {
        CapturingJdVopClient transport = new CapturingJdVopClient(Map.of(
                "vopOrderRpcResult", Map.of(
                        "success", true,
                        "resultCode", "0",
                        "result", Map.of(
                                "jdOrderId", 9001L,
                                "thirdOrderId", "AFJD001",
                                "parentType", 0,
                                "orderPrice", Map.of("orderTotalPrice", "12.34", "orderTotalFreight", "0.00"),
                                "skuInfoList", List.of(Map.of(
                                        "skuId", 1001L, "skuNum", 2, "skuPrice", "6.17"))))));
        DefaultJdVopOrderClient client = new DefaultJdVopOrderClient(new JdVopProperties(), transport);

        JdOrderResult result = client.submitOrder(new JdOrderSubmitRequest(
                "AFJD001", List.of(new JdOrderSubmitRequest.SkuInfo(1001L, 2)),
                new JdOrderSubmitRequest.ConsigneeInfo(
                        "测试用户", "13800000000", 1L, 72L, 2799L, null, "测试路 1 号")));

        assertEquals(9001L, result.jdOrderId());
        assertEquals("jingdong.vop.order.submitOrder", transport.apiMethod);
        Map<?, ?> requestRoot = (Map<?, ?>) transport.applicationParams;
        Map<?, ?> submitRequest = (Map<?, ?>) requestRoot.get("submitOrderOpenReq");
        assertEquals(1, submitRequest.get("submitStateType"));
        assertEquals(Map.of("paymentType", 4), submitRequest.get("paymentInfo"));
        Map<?, ?> consignee = (Map<?, ?>) submitRequest.get("consigneeInfo");
        assertEquals(0L, consignee.get("consigneeTownId"));
    }

    /** 京东多返回非请求 SKU 时必须拒绝确认履约成功。 */
    @Test
    void submitOrder_rejectsUnexpectedSku() {
        CapturingJdVopClient transport = new CapturingJdVopClient(Map.of(
                "vopOrderRpcResult", Map.of(
                        "success", true,
                        "resultCode", "0",
                        "result", Map.of(
                                "jdOrderId", 9001L,
                                "thirdOrderId", "AFJD001",
                                "skuInfoList", List.of(
                                        Map.of("skuId", 1001L, "skuNum", 2),
                                        Map.of("skuId", 1002L, "skuNum", 1))))));
        DefaultJdVopOrderClient client = new DefaultJdVopOrderClient(new JdVopProperties(), transport);
        JdOrderSubmitRequest request = new JdOrderSubmitRequest(
                "AFJD001", List.of(new JdOrderSubmitRequest.SkuInfo(1001L, 2)),
                new JdOrderSubmitRequest.ConsigneeInfo(
                        "测试用户", "13800000000", 1L, 72L, 2799L, null, "测试路 1 号"));

        assertThrows(JdVopException.class, () -> client.submitOrder(request));
    }

    /**
     * 捕获业务参数并把测试响应转换为客户端声明的私有响应类型。
     */
    private static final class CapturingJdVopClient implements JdVopClient {

        /** JSON 转换器。 */
        private final ObjectMapper objectMapper = new ObjectMapper();
        /** 预设响应。 */
        private final Object response;
        /** 捕获的接口方法。 */
        private String apiMethod;
        /** 捕获的业务参数。 */
        private Object applicationParams;

        private CapturingJdVopClient(Object response) {
            this.response = response;
        }

        @Override
        public JsonNode execute(String apiMethod, Object applicationParams) {
            this.apiMethod = apiMethod;
            this.applicationParams = applicationParams;
            return objectMapper.valueToTree(response);
        }

        @Override
        public JsonNode execute(URI serverUrl, String apiMethod, Object applicationParams) {
            return execute(apiMethod, applicationParams);
        }

        @Override
        public <T> T execute(String apiMethod, Object applicationParams, Class<T> responseType) {
            this.apiMethod = apiMethod;
            this.applicationParams = applicationParams;
            return objectMapper.convertValue(response, responseType);
        }

        @Override
        public <T> T execute(URI serverUrl, String apiMethod, Object applicationParams,
                             Class<T> responseType) {
            return execute(apiMethod, applicationParams, responseType);
        }
    }

}
