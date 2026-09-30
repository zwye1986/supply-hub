package com.aifuli.module.supplychain.api.movie;

import lombok.Getter;
import lombok.Setter;

/**
 * 仅在受控电影RPC中传递的单轮账号快照，不生成toString，不用于管理端或持久化。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#数据库账号与渠道选择落地2026-09-29
 */
@Getter
@Setter
public class MovieSupplierAccountDTO {
    /** JSON内稳定账号引用，与现有排期空间一致。 */
    private String accountRef;
    /** JSON内电影渠道，须与连接器适配类型一致。 */
    private MovieSupplierChannel channel;
    /** 同一账号的配置版本引用，格式为v加连接器业务版本号。 */
    private String configVersion;
    /** 供应商API基础地址。 */
    private String baseUrl;
    /** JSON内应用标识，JC为partnerCode，JY为X-Ca-Key，LX为app_id。 */
    private String appId;
    /** 中台解析的签名密钥，只允许电影服务在内存签名时使用。 */
    private String appSecret;
    /** 连接超时时间，毫秒。 */
    private Integer connectTimeoutMs;
    /** 读取超时时间，毫秒。 */
    private Integer readTimeoutMs;
    /** JSON内同账号请求最小起始间隔，毫秒。 */
    private Integer minRequestIntervalMs;
    /** JSON内JC订单类型，不解释为分钟SLA。 */
    private String deliverMinutes;
}
