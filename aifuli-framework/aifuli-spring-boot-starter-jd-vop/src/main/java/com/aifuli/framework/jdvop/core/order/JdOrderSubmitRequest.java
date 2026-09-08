package com.aifuli.framework.jdvop.core.order;

import java.util.List;

/**
 * 京东 VOP 提交订单请求。
 *
 * @param thirdOrderId 商城侧稳定幂等单号
 * @param skuInfoList 京东 SKU 及数量
 * @param consigneeInfo 京东收货信息
 */
public record JdOrderSubmitRequest(
        String thirdOrderId,
        List<SkuInfo> skuInfoList,
        ConsigneeInfo consigneeInfo) {

    public JdOrderSubmitRequest {
        if (thirdOrderId == null || thirdOrderId.isBlank()) {
            throw new IllegalArgumentException("京东第三方订单号不能为空");
        }
        if (skuInfoList == null || skuInfoList.isEmpty()) {
            throw new IllegalArgumentException("京东下单 SKU 不能为空");
        }
        skuInfoList = List.copyOf(skuInfoList);
        if (consigneeInfo == null) {
            throw new IllegalArgumentException("京东收货信息不能为空");
        }
    }

    /**
     * 京东订单商品。
     *
     * @param skuId 京东 SKU 编号
     * @param skuNum 购买数量
     */
    public record SkuInfo(Long skuId, Integer skuNum) {

        public SkuInfo {
            if (skuId == null || skuId <= 0) {
                throw new IllegalArgumentException("京东 SKU 编号必须大于 0");
            }
            if (skuNum == null || skuNum <= 0) {
                throw new IllegalArgumentException("京东 SKU 数量必须大于 0");
            }
        }
    }

    /**
     * 京东订单收货信息。
     *
     * @param name 收件人
     * @param mobile 收件手机号
     * @param provinceId 京东省编号
     * @param cityId 京东市编号
     * @param countyId 京东区县编号
     * @param townId 京东乡镇编号，无四级地址时可为空
     * @param address 详细地址
     */
    public record ConsigneeInfo(
            String name,
            String mobile,
            Long provinceId,
            Long cityId,
            Long countyId,
            Long townId,
            String address) {

        public ConsigneeInfo {
            validateText(name, "京东收件人不能为空");
            validateText(mobile, "京东收件手机号不能为空");
            validateAreaId(provinceId, "省");
            validateAreaId(cityId, "市");
            validateAreaId(countyId, "区县");
            if (townId != null && townId <= 0) {
                throw new IllegalArgumentException("京东乡镇编号必须大于 0");
            }
            validateText(address, "京东收件详细地址不能为空");
        }

        /** 校验京东必填文本。 */
        private static void validateText(String value, String message) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(message);
            }
        }

        /** 校验京东必填行政区编号。 */
        private static void validateAreaId(Long areaId, String areaLevel) {
            if (areaId == null || areaId <= 0) {
                throw new IllegalArgumentException("京东" + areaLevel + "编号必须大于 0");
            }
        }
    }

}
