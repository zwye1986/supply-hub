package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyMonitorMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.*;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.FREQUENCY_PARAMETER_INVALID;

/**
 * 全表统计与数据库分页；输出只含显式安全字段，不序列化DO或身份摘要。
 * 每次请求读取DB时间，同一响应复用observedAt；不同HTTP不承诺共享快照。
 * 设计见 {@code docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#7-管理端契约与权限}。
 */
@Service @RequiredArgsConstructor @Slf4j
public class RechargeFrequencyMonitorQueryService {
    private static final String BLOCKED_DISPLAY_STATUS="BLOCKED";
    public enum ReleaseBlockedReason { MIGRATION_BLOCKED, SUCCESS_WINDOW, UNCONFIRMED_SEND_RISK }
    private final RechargeFrequencyMonitorMapper mapper;
    private final RechargeFrequencyIdentityService identity;
    private final SupplyChainDatabaseClock clock;

    @Transactional(transactionManager="supplyChainTransactionManager", readOnly=true)
    public Statistics statistics(Long actor) {
        log.info("[限频管理统计] actor={}", actor);
        LocalDateTime now = clock.now();
        return mapper.statistics(new Filter(1, 20, null, null, null, null, null, null, null, null, now));
    }
    @Transactional(transactionManager="supplyChainTransactionManager", readOnly=true)
    public ObservedPage<?> page(Long actor, View view, Filter filter, String mobile) {
        log.info("[限频管理列表] actor={}, view={}, pageNo={}, pageSize={}", actor, view, filter.pageNo(), filter.pageSize());
        if (filter.pageNo() < 1 || filter.pageNo() > 1000000 || filter.pageSize() < 1 || filter.pageSize() > 100
                || filter.enqueuedFrom() != null && filter.enqueuedTo() != null && filter.enqueuedFrom().isAfter(filter.enqueuedTo())) {
            throw exception(FREQUENCY_PARAMETER_INVALID);
        }
        // TODO: docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#7-管理端契约与权限
        // 积压项级筛选聚合口径待用户确认；先显式拒绝，不能静默忽略或猜测全队列/局部数。
        if (view == View.BACKLOGS && (filter.buyerId()!=null || filter.projectId()!=null || filter.orderNo()!=null
                || filter.buyerOrderNo()!=null || filter.enqueuedFrom()!=null || filter.enqueuedTo()!=null)) { throw exception(FREQUENCY_PARAMETER_INVALID); }
        byte[] digest = null;
        if (mobile != null) {
            if (FulfillmentTargetFields.normalizeMobile(mobile) == null) { throw exception(FREQUENCY_PARAMETER_INVALID); }
            digest = identity.identify(mobile).mobileDigest();
        }
        var f = new Filter(filter.pageNo(), filter.pageSize(), filter.buyerId(), filter.projectId(), filter.platformSpuCode(),
                filter.orderNo(), filter.buyerOrderNo(), filter.enqueuedFrom(), filter.enqueuedTo(), digest, clock.now());
        return switch (view) {
            case WAITING -> new ObservedPage<>(new PageResult<>(mapper.waiting(f), mapper.waitingCount(f)), f.observedAt());
            case OCCUPANCIES -> {
                List<Occupancy> rows = mapper.occupancies(f);
                for (var row : rows) { row.describeRelease(f.observedAt());
                    if (row.getBlockedReason() != null) { row.setRiskOrderNos(mapper.riskOrders(row.getQueueId(),RechargeFrequencyEntryStatus.HOLDING.getCode(),RechargeFrequencyDispatchPhase.SEND_POSSIBLE.getCode())); } }
                yield new ObservedPage<>(new PageResult<>(rows, mapper.occupancyCount(f)), f.observedAt());
            }
            case BACKLOGS -> new ObservedPage<>(new PageResult<>(mapper.backlogs(f), mapper.backlogCount(f)), f.observedAt());
        };
    }
    public enum View { WAITING, OCCUPANCIES, BACKLOGS }
    /** 精确摘要仅为SQL参数；toString不泄漏身份，枚举码统一绑定供XML使用。 */
    public record Filter(
            /** 页码，从1开始。 */ int pageNo,
            /** 每页条数，默认20，最大100。 */ int pageSize,
            /** 分销商内部主键。 */ Long buyerId,
            /** 采购项目内部主键；存量订单可空。 */ Long projectId,
            /** 平台SPU永久业务编码。 */ String platformSpuCode,
            /** 供应链总订单号。 */ String orderNo,
            /** 采购商侧C端订单号，CARD场景必填。 */ String buyerOrderNo,
            /** 首次入队时间范围开始，Asia/Shanghai，包含边界。 */ LocalDateTime enqueuedFrom,
            /** 首次入队时间范围截止，Asia/Shanghai，包含边界。 */ LocalDateTime enqueuedTo,
            /** 规范化充值手机号的专用 HMAC 摘要；仅内部SQL条件。 */ byte[] mobileDigest,
            /** 本次查询观察时间，Asia/Shanghai，来自数据库时钟。 */ LocalDateTime observedAt) {
        public long getOffset() { return (long)(pageNo - 1) * pageSize; }
        public short getWaitingStatus() { return RechargeFrequencyEntryStatus.WAITING.getCode(); }
        public short getHoldingStatus() { return RechargeFrequencyEntryStatus.HOLDING.getCode(); }
        public short getSendPossiblePhase() { return RechargeFrequencyDispatchPhase.SEND_POSSIBLE.getCode(); }
        public short getFulfillingStatus() { return FulfillmentRequestStatus.FULFILLING.getCode(); }
        public short getPreparedPhase() { return RechargeFrequencyDispatchPhase.PREPARED.getCode(); }
        public short getSuccessStatus() { return FulfillmentRequestStatus.SUCCEEDED.getCode(); }
        public short getFailedStatus() { return FulfillmentRequestStatus.FAILED.getCode(); }
        public short getExecutingStatus() { return com.aifuli.module.supplychain.border.order.domain.SupplierOrderOperationStatus.EXECUTING.getCode(); }
        public short getPendingStatus() { return com.aifuli.module.supplychain.border.order.domain.SupplierOrderOperationStatus.PENDING.getCode(); }
        public short getClaimedStatus() { return com.aifuli.module.supplychain.border.order.domain.SupplierOrderOperationStatus.CLAIMED.getCode(); }
        public short getOperationSuccessStatus() { return com.aifuli.module.supplychain.border.order.domain.SupplierOrderOperationStatus.SUCCEEDED.getCode(); }
        public short getOperationFailedStatus() { return com.aifuli.module.supplychain.border.order.domain.SupplierOrderOperationStatus.FAILED.getCode(); }
        public String getSuccessResult() { return OperationOutcomeClass.SUCCEEDED.name(); }
        public String getRejectedResult() { return OperationOutcomeClass.REJECTED.name(); }
        public String getFulfillKind() { return FulfillmentOperationKind.FULFILL.name(); }
        public String getSendStep() { return AdapterStepCode.SEND.name(); }
        public String getSendKmStep() { return AdapterStepCode.SEND_KM.name(); }
        public String getQueryOrderKind() { return FulfillmentOperationKind.QUERY_ORDER.name(); }
        public String getQueryAssetKind() { return FulfillmentOperationKind.QUERY_ASSET.name(); }
        public long getBacklogThreshold() { return RechargeFrequencyConstants.BACKLOG_THRESHOLD; }
        public long getOccupiedAlertSeconds() { return RechargeFrequencyConstants.OCCUPIED_ALERT_SECONDS; }
        public long getSuccessMinimumHoldSeconds() { return RechargeFrequencyConstants.SUCCESS_MINIMUM_HOLD_SECONDS; }
        @Override public String toString() { return "FrequencyFilter[pageNo=" + pageNo + ",pageSize=" + pageSize + "]"; }
    }
    /** JSON展开已有PageResult以保持管理端data.list/data.total契约。 */
    public record ObservedPage<T>(
            /** 数据库分页结果，JSON展开为list与total。 */ @JsonUnwrapped PageResult<T> page,
            /** 本次查询观察时间，Asia/Shanghai，来自数据库时钟。 */ LocalDateTime observedAt) { }
    @Data public static class Statistics {
        /** 全中台WAITING订单数。 */ private long waitingOrderCount;
        /** 持有占用或迁移阻塞的队列数。 */ private long occupiedQueueCount;
        /** WAITING数严格超过积压阈值的队列数。 */ private long overThresholdQueueCount;
        /** 从原占用取得时间严格超过告警时长的队列数。 */ private long overdueQueueCount;
        /** 成功最短占用秒数，按原acquiredAt计算。 */ private long successMinimumHoldSeconds;
        /** 持续占用告警阈值秒数。 */ private long occupiedAlertSeconds;
        /** 队列积压阈值，严格超过才告警。 */ private long backlogThreshold;
        /** 本次查询观察时间，Asia/Shanghai，来自数据库时钟。 */ private LocalDateTime observedAt;
    }
    @Data public static class WaitingOrder {
        /** 直充限频队列项内部主键。 */ private Long entryId;
        /** 直充限频队列内部主键。 */ private Long queueId;
        /** 乐观锁版本。 */ private Long version;
        /** 充值手机号脱敏展示值。 */ private String maskedMobile;
        /** 平台SPU永久业务编码。 */ private String platformSpuCode;
        /** 平台SPU标准名称。 */ private String platformSpuName;
        /** 供应链总订单号。 */ private String orderNo;
        /** 采购商侧C端订单号，CARD场景必填。 */ private String buyerOrderNo;
        /** 分销商内部主键。 */ private Long buyerId;
        /** 采购商永久业务编码。 */ private String buyerCode;
        /** 采购商标准名称。 */ private String buyerName;
        /** 采购项目内部主键；存量订单可空。 */ private Long projectId;
        /** 采购项目全局永久编码。 */ private String projectCode;
        /** 采购项目名称。 */ private String projectName;
        /** 首次入队时间，Asia/Shanghai。 */ private LocalDateTime enqueuedAt;
        /** 截至observedAt已等待秒数，不小于零。 */ private long waitSeconds;
        /** 本订单首次入队分配的队列序号。 */ private long queueSequence;
    }
    @Data public static class Backlog {
        /** 直充限频队列内部主键。 */ private Long queueId;
        /** 充值手机号脱敏展示值。 */ private String maskedMobile;
        /** 平台SPU永久业务编码。 */ private String platformSpuCode;
        /** 平台SPU标准名称。 */ private String platformSpuName;
        /** 本队列WAITING项数量。 */ private long waitingCount;
        /** 本队列WAITING项最早首次入队时间，Asia/Shanghai。 */ private LocalDateTime earliestEnqueuedAt;
        /** WAITING项数量是否严格超过积压阈值。 */ private boolean overThreshold;
    }
    @Data public static class Occupancy {
        /** 直充限频队列内部主键。 */ private Long queueId;
        /** 充值手机号脱敏展示值。 */ private String maskedMobile;
        /** 平台SPU永久业务编码。 */ private String platformSpuCode;
        /** 平台SPU标准名称。 */ private String platformSpuName;
        /** 供应链总订单号。 */ private String holderOrderNo;
        /** 履约请求状态名称；迁移阻塞单独展示BLOCKED。 */ private String displayStatus;
        /** 当前占用取得时间，Asia/Shanghai。 */ private LocalDateTime acquiredAt;
        /** 本次成功最短占用截止时间，Asia/Shanghai。 */ private LocalDateTime minimumHoldUntil;
        /** 截至observedAt已占用秒数；迁移无取得时间时为空。 */ private Long heldSeconds;
        /** 是否严格超过持续占用告警时长。 */ private boolean overdue;
        /** 每次取得新占用递增的占用代次。 */ private Long generation;
        /** 乐观锁版本。 */ private Long version;
        /** 只读展示是否具备可信释放资格；命令事务仍再次校验。 */ private boolean canRelease;
        /** 无可信释放资格的稳定原因码。 */ private String releaseBlockedReason;
        /** 迁移阻塞队列的待核实供应链总订单号列表。 */ private List<String> riskOrderNos;
        /** 队列阻塞原因；内部投影，不能回显未校验原文。 */ @JsonIgnore private String blockedReason;
        /** 履约请求状态；内部投影。 */ @JsonIgnore private Short requestStatus;
        /** 同单同请求的可信终态证据；内部投影。 */ @JsonIgnore private boolean terminalEvidence;
        /** 所有关联操作无未确认发送风险且当前仍PREPARED；内部投影。 */ @JsonIgnore private boolean preparedSafe;
        void describeRelease(LocalDateTime now) {
            if (blockedReason != null) { displayStatus=BLOCKED_DISPLAY_STATUS; releaseBlockedReason=ReleaseBlockedReason.MIGRATION_BLOCKED.name(); return; }
            if (requestStatus != null) { displayStatus = FulfillmentRequestStatus.fromCode(requestStatus).name(); }
            if (terminalEvidence && requestStatus != null && requestStatus == FulfillmentRequestStatus.SUCCEEDED.getCode()
                    && (acquiredAt == null || now.isBefore(acquiredAt.plusSeconds(RechargeFrequencyConstants.SUCCESS_MINIMUM_HOLD_SECONDS)))) {
                releaseBlockedReason=ReleaseBlockedReason.SUCCESS_WINDOW.name(); return;
            }
            canRelease = terminalEvidence || preparedSafe;
            releaseBlockedReason=canRelease ? null : ReleaseBlockedReason.UNCONFIRMED_SEND_RISK.name();
        }
    }
}
