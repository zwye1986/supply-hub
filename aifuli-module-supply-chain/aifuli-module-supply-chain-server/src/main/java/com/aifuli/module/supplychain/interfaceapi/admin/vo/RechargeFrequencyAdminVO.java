package com.aifuli.module.supplychain.interfaceapi.admin.vo;

import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDateTime;

/**
 * 内部运营限频请求；不接收操作人或客户端释放证据，手机号仅进入POST正文。
 * 设计见 {@code docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#7-管理端契约与权限}。
 */
public final class RechargeFrequencyAdminVO {
    private RechargeFrequencyAdminVO() { }
    public enum View { WAITING, OCCUPANCIES, BACKLOGS }

    @Data
    public static class PageRequest {
        /** 页码，从1开始。 */
        @Min(1) @Max(1000000) private int pageNo = 1;
        /** 每页条数，默认20，最大100。 */
        @Min(1) @Max(100) private int pageSize = 20;
        /** 分销商内部主键。 */
        @Positive private Long buyerId;
        /** 采购项目内部主键；存量订单可空。 */
        @Positive private Long projectId;
        /** 平台SPU永久业务编码。 */
        @Size(max=64) private String platformSpuCode;
        /** 供应链总订单号。 */
        @Size(max=64) private String orderNo;
        /** 采购商侧C端订单号，CARD场景必填。 */
        @Size(max=128) private String buyerOrderNo;
        /** 首次入队时间，Asia/Shanghai。 */
        @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) private LocalDateTime enqueuedFrom;
        /** 首次入队时间范围截止，Asia/Shanghai，包含边界。 */
        @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) private LocalDateTime enqueuedTo;
    }
    @Data @EqualsAndHashCode(callSuper=true)
    @ToString(onlyExplicitlyIncluded=true)
    public static class SearchRequest extends PageRequest {
        /** 精确查找的实际充值手机号；只允许POST正文，不持久化或记录日志。 */
        @NotBlank @Size(max=32) private String mobile;
        /** 查询视图封闭枚举：WAITING、OCCUPANCIES、BACKLOGS。 */
        @NotNull private View view;
    }
    public record RetryRequest(
            /** 管理操作幂等号。 */ @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String actionNo,
            /** 请求预期的乐观锁版本。 */ @NotNull @PositiveOrZero Long expectedVersion,
            /** 管理操作原因。 */ @NotBlank @Size(max=512) String reason) {
        @Override public String toString() { return "RetryRequest[expectedVersion=" + expectedVersion + "]"; }
    }
    public record ReleaseRequest(
            /** 管理操作幂等号。 */ @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String actionNo,
            /** 供应链总订单号。 */ @NotBlank @Size(max=64) String expectedHolderOrderNo,
            /** 请求预期的占用代次。 */ @NotNull @PositiveOrZero Long expectedGeneration,
            /** 请求预期的乐观锁版本。 */ @NotNull @PositiveOrZero Long expectedVersion,
            /** 管理操作原因。 */ @NotBlank @Size(max=512) String reason) {
        @Override public String toString() { return "ReleaseRequest[expectedGeneration=" + expectedGeneration + ", expectedVersion=" + expectedVersion + "]"; }
    }
}
