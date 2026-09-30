package com.aifuli.framework.common.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 外部开放接口统一返回结构。
 *
 * @param <T> 业务数据类型
 */
@Schema(description = "外部开放接口统一返回结果")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OpenApiResult<T> {

    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_FAIL = "fail";
    public static final String SUCCESS_CODE = "0";

    @Schema(description = "处理状态：success/fail", requiredMode = Schema.RequiredMode.REQUIRED, example = "success")
    private String status;

    @Schema(description = "错误码；成功固定为 0", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    private String errCode;

    @Schema(description = "错误信息；成功为空字符串", requiredMode = Schema.RequiredMode.REQUIRED, example = "")
    private String errMsg;

    @Schema(description = "业务数据")
    private T data;

    /**
     * 构造成功返回。
     *
     * @param data 业务数据
     * @return 开放接口成功结果
     */
    public static <T> OpenApiResult<T> success(T data) {
        return new OpenApiResult<>(STATUS_SUCCESS, SUCCESS_CODE, "", data);
    }

    /**
     * 构造失败返回。
     *
     * @param errCode 错误码
     * @param errMsg  错误信息
     * @return 开放接口失败结果
     */
    public static <T> OpenApiResult<T> fail(String errCode, String errMsg) {
        return new OpenApiResult<>(STATUS_FAIL, errCode, errMsg, null);
    }

}
