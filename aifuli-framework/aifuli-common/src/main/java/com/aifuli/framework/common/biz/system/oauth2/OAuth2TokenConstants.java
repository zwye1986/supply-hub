package com.aifuli.framework.common.biz.system.oauth2;

import com.aifuli.framework.common.exception.ErrorCode;

/**
 * OAuth2 Token 扩展常量。
 */
public interface OAuth2TokenConstants {

    /** Token 用户信息中的登录来源字段。 */
    String USER_INFO_LOGIN_SOURCE = "loginSource";
    /** Token 用户信息中的平台编码字段。 */
    String USER_INFO_PLATFORM_CODE = "platformCode";

    /** 自有商城登录来源。 */
    String LOGIN_SOURCE_SELF = "SELF";
    /** 外部平台免登来源。 */
    String LOGIN_SOURCE_PLATFORM = "PLATFORM";

    /** 平台免登会话已过期，H5 应通知外部平台重新获取免登地址。 */
    ErrorCode PLATFORM_SSO_EXPIRED = new ErrorCode(40101, "平台登登录已过期，请重新授权登录");

}
