package com.aifuli.framework.jdvop.core.product;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 京东四级地址。
 *
 * @param provinceId 省 ID
 * @param cityId 市 ID
 * @param countyId 区县 ID
 * @param townId 乡镇 ID；无四级地址时可为空
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JdAreaInfo(
        @JsonProperty("provinceId") Long provinceId,
        @JsonProperty("cityId") Long cityId,
        @JsonProperty("countyId") Long countyId,
        @JsonProperty("townId") Long townId) {

    public JdAreaInfo {
        validateRequiredAreaId(provinceId, "省");
        validateRequiredAreaId(cityId, "市");
        validateRequiredAreaId(countyId, "区县");
        if (townId != null && townId <= 0) {
            throw new IllegalArgumentException("京东乡镇 ID 必须大于 0");
        }
    }

    /**
     * 校验京东必填地址 ID。
     */
    private static void validateRequiredAreaId(Long areaId, String areaLevel) {
        if (areaId == null || areaId <= 0) {
            throw new IllegalArgumentException("京东" + areaLevel + " ID 必须大于 0");
        }
    }
}
