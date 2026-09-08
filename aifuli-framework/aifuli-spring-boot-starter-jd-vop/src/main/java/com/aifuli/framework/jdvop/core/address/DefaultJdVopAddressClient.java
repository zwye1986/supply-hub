package com.aifuli.framework.jdvop.core.address;

import com.aifuli.framework.jdvop.config.JdVopProperties;
import com.aifuli.framework.jdvop.core.client.JdVopClient;
import com.aifuli.framework.jdvop.core.exception.JdVopException;
import com.aifuli.framework.jdvop.core.product.JdAreaInfo;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Set;

/**
 * 京东 VOP 地址客户端默认实现。
 */
@Slf4j
public class DefaultJdVopAddressClient implements JdVopAddressClient {

    /**
     * 京东业务成功码，兼容不同 VOP 网关返回的 0 和 0000。
     */
    private static final Set<String> SUCCESS_RESULT_CODES = Set.of("0", "0000");

    /** 京东四级地址解析接口方法名。 */
    private static final String ADDRESS_CONVERT_METHOD =
            "jingdong.vop.address.convertFourAreaByDetailStr";

    private final JdVopProperties properties;
    private final JdVopClient jdVopClient;

    public DefaultJdVopAddressClient(JdVopProperties properties, JdVopClient jdVopClient) {
        this.properties = properties;
        this.jdVopClient = jdVopClient;
    }

    @Override
    public JdAreaInfo convertFourAreaByDetailAddress(String addressDetail) {
        if (!StringUtils.hasText(addressDetail)) {
            throw new IllegalArgumentException("用于京东地址解析的完整收货地址不能为空");
        }
        String normalizedAddress = addressDetail.trim();
        // 与 fushop 的京东 SDK 调用保持一致，通过 Router 提交 addressDetailStr 参数。
        log.info("京东四级地址解析请求：url={}, method={}, address={}",
                properties.getAddressConvertUrl(), ADDRESS_CONVERT_METHOD, normalizedAddress);
        AddressConvertResponse response = jdVopClient.execute(
                properties.getAddressConvertUrl(), ADDRESS_CONVERT_METHOD,
                Map.of("addressDetailStr", normalizedAddress), AddressConvertResponse.class);
        if (response == null) {
            throw new JdVopException("京东四级地址解析返回为空");
        }

        AddressConvertRpcResult rpcResult = response.openRpcResult();
        if (rpcResult == null) {
            throw new JdVopException("京东四级地址解析缺少 openRpcResult");
        }
        JdAreaConvertResult result = rpcResult.result();
        // INFO 仅记录京东区域编号和业务结果，便于在不调整日志级别时排查地址转换。
        log.info("京东四级地址解析响应：success={}, resultCode={}, resultMessage={}, "
                        + "provinceId={}, cityId={}, countyId={}, townId={}",
                rpcResult.success(), rpcResult.resultCode(), rpcResult.resultMessage(),
                result == null ? null : result.provinceId(),
                result == null ? null : result.cityId(),
                result == null ? null : result.countyId(),
                result == null ? null : result.townId());
        if (!Boolean.TRUE.equals(rpcResult.success())
                || !SUCCESS_RESULT_CODES.contains(rpcResult.resultCode())) {
            throw new JdVopException(rpcResult.resultCode(),
                    "京东四级地址解析失败：" + defaultMessage(rpcResult.resultMessage()));
        }
        if (result == null) {
            throw new JdVopException(rpcResult.resultCode(), "京东四级地址解析未返回地址结果");
        }
        // 京东未匹配到四级乡镇时可能返回空或 0，统一按可选字段处理。
        Long townId = result.townId() != null && result.townId() > 0 ? result.townId() : null;
        return new JdAreaInfo(result.provinceId(), result.cityId(), result.countyId(), townId);
    }

    private static String defaultMessage(String message) {
        return StringUtils.hasText(message) ? message : "未知错误";
    }

    private record AddressConvertResponse(
            @JsonProperty("openRpcResult") AddressConvertRpcResult openRpcResult) {
    }

    private record AddressConvertRpcResult(
            @JsonProperty("success") Boolean success,
            @JsonProperty("resultCode") String resultCode,
            @JsonProperty("resultMessage") String resultMessage,
            @JsonProperty("result") JdAreaConvertResult result) {
    }

    private record JdAreaConvertResult(
            Long provinceId,
            Long cityId,
            Long countyId,
            Long townId) {
    }

}
