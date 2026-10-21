package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.supply.domain.MerchantQuotaPlatform;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.LocalDateTime;
import java.util.List;
import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.*;

/**
 * 商户额度管理投影与命令适配；写入只委托唯一所有者，不在外层分离追加审计。
 * 账号身份永不修改，按id读出后调额；历史缺口用最早真实版本表达，不补造历史。
 * 设计：docs/superpowers/specs/2026-10-19-merchant-quota-admin-design.md#3-管理接口。
 */
@Service @RequiredArgsConstructor
public class MerchantQuotaAdminService {
    private static final String TX = "supplyChainTransactionManager";
    private final MerchantQuotaService commands;
    private final MerchantQuotaMapper quotas;
    private final MerchantQuotaAuditMapper audits;

    @Transactional(transactionManager = TX, readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<QuotaView> page(String platformCode, String merchantAccount, int pageNo, int pageSize) {
        var page = pageParam(pageNo, pageSize);
        if (platformCode != null) platform(platformCode);
        if (merchantAccount != null && (merchantAccount.isBlank() || merchantAccount.length() > 128)) throw exception(MERCHANT_QUOTA_PARAMETER_INVALID);
        var rows = quotas.selectPage(page, new LambdaQueryWrapper<MerchantQuotaDO>()
                .eq(platformCode != null, MerchantQuotaDO::getPlatformCode, platformCode)
                .eq(merchantAccount != null, MerchantQuotaDO::getMerchantAccount, merchantAccount)
                .orderByDesc(MerchantQuotaDO::getId));
        return new PageResult<>(rows.getList().stream().map(MerchantQuotaAdminService::view).toList(), rows.getTotal());
    }

    public QuotaView get(long id) { return view(require(id)); }

    public ChangeView create(String platformCode, String merchantAccount, int rate, int concurrency, String reason, Long operatorId) {
        return configure(new MerchantQuotaService.Configure(platform(platformCode), merchantAccount, rate, concurrency, null, operatorId, reason));
    }

    public ChangeView update(long id, int rate, int concurrency, Long expectedVersion, String reason, Long operatorId) {
        if (expectedVersion == null || expectedVersion < 0) throw exception(MERCHANT_QUOTA_PARAMETER_INVALID);
        var row = require(id);
        return configure(new MerchantQuotaService.Configure(MerchantQuotaPlatform.valueOf(row.getPlatformCode()), row.getMerchantAccount(),
                rate, concurrency, expectedVersion, operatorId, reason));
    }

    /** 一致快照保证最早真实版本、总数和本页来自同一历史视图；无历史不等于新建事件。 */
    @Transactional(transactionManager = TX, readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public HistoryView history(long id, int pageNo, int pageSize) {
        var page = pageParam(pageNo, pageSize);
        require(id);
        var first = audits.selectOne(new LambdaQueryWrapper<MerchantQuotaAuditDO>().eq(MerchantQuotaAuditDO::getQuotaId, id)
                .orderByAsc(MerchantQuotaAuditDO::getQuotaVersion).last("LIMIT 1"));
        var rows = audits.selectPage(page, new LambdaQueryWrapper<MerchantQuotaAuditDO>().eq(MerchantQuotaAuditDO::getQuotaId, id)
                .orderByDesc(MerchantQuotaAuditDO::getQuotaVersion));
        return new HistoryView(rows.getList().stream().map(a -> new AuditView(a.getId(), a.getBeforeRateLimitPerSecond(), a.getAfterRateLimitPerSecond(),
                a.getBeforeConcurrencyLimit(), a.getAfterConcurrencyLimit(), a.getQuotaVersion(), a.getOperatorId(), a.getReason(), a.getOccurredAt())).toList(),
                rows.getTotal(), first == null ? null : first.getQuotaVersion());
    }

    private ChangeView configure(MerchantQuotaService.Configure input) {
        try {
            var result = commands.configure(input);
            return new ChangeView(result.id(), result.version(), result.rateLimitPerSecond(), result.concurrencyLimit());
        } catch (MerchantQuotaService.VersionConflict conflict) {
            throw withCause(exception(MERCHANT_QUOTA_VERSION_CONFLICT), conflict);
        } catch (IllegalArgumentException invalid) {
            throw withCause(exception(MERCHANT_QUOTA_PARAMETER_INVALID), invalid);
        }
    }
    private static ServiceException withCause(ServiceException target, RuntimeException cause) { target.initCause(cause); return target; }
    private MerchantQuotaDO require(long id) {
        var row = quotas.selectById(id);
        if (row == null) throw exception(MERCHANT_QUOTA_NOT_FOUND);
        return row;
    }
    private static MerchantQuotaPlatform platform(String code) {
        if (code != null) {
            try { return MerchantQuotaPlatform.valueOf(code); }
            catch (IllegalArgumentException ignored) { /* 闭集业务输入，在管理边界统一拒绝。 */ }
        }
        throw exception(MERCHANT_QUOTA_PARAMETER_INVALID);
    }
    private static PageParam pageParam(int pageNo, int pageSize) {
        if (pageNo < 1 || pageSize < 1 || pageSize > 100) throw exception(MERCHANT_QUOTA_PARAMETER_INVALID);
        var page = new PageParam(); page.setPageNo(pageNo); page.setPageSize(pageSize); return page;
    }
    private static QuotaView view(MerchantQuotaDO row) {
        return new QuotaView(row.getId(), row.getPlatformCode(), row.getMerchantAccount(), row.getRateLimitPerSecond(), row.getConcurrencyLimit(),
                row.getVersion(), row.getUpdatedBy(), row.getUpdatedAt(), row.getChangeReason());
    }

    public record ChangeView(@JsonFormat(shape = JsonFormat.Shape.STRING) Long id,
                             @JsonFormat(shape = JsonFormat.Shape.STRING) Long version,
                             /** 每秒成功准入上限 */ int rateLimitPerSecond,
                             /** 同步调用并发占用上限 */ int concurrencyLimit) { }
    public record QuotaView(@JsonFormat(shape = JsonFormat.Shape.STRING) Long id,
                            /** 供应商平台码 */ String platformCode,
                            /** 非秘密供应商商户账号，原值精确匹配 */ String merchantAccount,
                            /** 每秒成功准入上限 */ int rateLimitPerSecond,
                            /** 同步调用并发占用上限 */ int concurrencyLimit,
                            /** 乐观锁版本号 */ @JsonFormat(shape = JsonFormat.Shape.STRING) Long version,
                            /** 最后修改人 */ @JsonFormat(shape = JsonFormat.Shape.STRING) Long updatedBy,
                            /** 最后修改时间，Asia/Shanghai */ LocalDateTime updatedAt,
                            /** 配置变更原因 */ String changeReason) { }
    public record AuditView(@JsonFormat(shape = JsonFormat.Shape.STRING) Long id,
                            /** 变更前每秒成功准入上限；首次创建为空 */ Integer beforeRateLimitPerSecond,
                            /** 变更后每秒成功准入上限 */ int afterRateLimitPerSecond,
                            /** 变更前同步调用并发占用上限；首次创建为空 */ Integer beforeConcurrencyLimit,
                            /** 变更后同步调用并发占用上限 */ int afterConcurrencyLimit,
                            /** 变更后的乐观锁版本号 */ @JsonFormat(shape = JsonFormat.Shape.STRING) Long quotaVersion,
                            /** 操作人用户ID */ @JsonFormat(shape = JsonFormat.Shape.STRING) Long operatorId,
                            /** 配置变更原因 */ String reason,
                            /** 操作时间，Asia/Shanghai */ LocalDateTime occurredAt) { }
    public record HistoryView(List<AuditView> list, long total,
                              @JsonFormat(shape = JsonFormat.Shape.STRING) Long firstRecordedVersion) { }
}
