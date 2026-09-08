package com.aifuli.framework.common.util.open;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 标准开放商城接口签名工具。
 */
public class OpenMallSignUtils {

    private static final String SIGN_FIELD = "sign";
    private static final String SIGN_TYPE_MD5 = "MD5";

    private OpenMallSignUtils() {
    }

    /**
     * 校验请求签名。
     *
     * @param paramBean   参数 Bean
     * @param secretKey   平台签名密钥
     * @param signType    签名方式
     * @param requestSign 请求签名
     * @return 是否验签通过
     */
    public static boolean verify(Object paramBean, String secretKey, String signType, String requestSign) {
        if (paramBean == null || StrUtil.isBlank(secretKey) || StrUtil.isBlank(requestSign)) {
            return false;
        }
        String actualSign = sign(BeanUtil.beanToMap(paramBean, false, true), secretKey, signType);
        return StrUtil.equals(actualSign, requestSign);
    }

    /**
     * 生成签名。
     *
     * 说明：标准开放接口使用 key 字典序、空值不参与、sign 不参与，
     * 最后追加 secretKey 后做 MD5 32 位小写。
     *
     * @param params    原始参数
     * @param secretKey 签名密钥
     * @param signType  签名方式
     * @return 签名结果
     */
    public static String sign(Map<String, Object> params, String secretKey, String signType) {
        TreeMap<String, String> signParams = params.entrySet().stream()
                .filter(entry -> !SIGN_FIELD.equals(entry.getKey()))
                .filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> Convert.toStr(entry.getValue()),
                        (oldValue, newValue) -> newValue, TreeMap::new));
        signParams.entrySet().removeIf(entry -> StrUtil.isBlank(entry.getValue()));
        String signContent = signParams.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
        signContent = StrUtil.isBlank(signContent) ? "secretKey=" + secretKey : signContent + "&secretKey=" + secretKey;

        String actualSignType = StrUtil.blankToDefault(signType, SIGN_TYPE_MD5);
        if (SIGN_TYPE_MD5.equalsIgnoreCase(actualSignType)) {
            return DigestUtil.md5Hex(signContent).toLowerCase(Locale.ROOT);
        }
        throw new IllegalArgumentException("暂不支持的开放接口签名方式：" + actualSignType);
    }

}
