package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.infrastructure.configuration.BuyerSelectionCatalogProperties;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerCatalogRefreshOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 有界公平扫描：只负责有界读取AUTO非退役目录ID并逐条调用同一刷新门面，不负责领域判定。
 * 内存位置与遍历上界（cursor, upperId]：遍历开始读当前最大符合范围ID冻结上界，逐批推进 cursor，
 * 每条已尝试即推进（即使刷新失败）；到上界或空页清空位置，下次从最小ID读新上界。
 * 不建数据库页记录、轮次、claim、租约或心跳；无选主，多实例靠刷新行锁与摘要唯一幂等收敛。
 * 设计见 docs/superpowers/specs/2026-10-09-saleable-catalog-order-acceptance-design.md#6-后台自动恢复有界周期扫描。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BuyerSelectionCatalogSweepService {

    /** 单批结果：attempted 已尝试条数；published 真实发布推进；failed 刷新异常；其余计入 skipped。 */
    public record SweepBatch(int attempted, int published, int failed, int skipped) {

        public SweepBatch plus(SweepBatch other) {
            return new SweepBatch(attempted + other.attempted, published + other.published,
                    failed + other.failed, skipped + other.skipped);
        }
    }

    private final BuyerListingMapper listingMapper;
    private final BuyerSelectionCatalogRefreshService refreshService;
    private final BuyerSelectionCatalogProperties properties;

    /** 内存扫描位置：已尝试到的最大主键；null 表示本遍历未开始。 */
    private Long cursor;
    /** 本次遍历主键上界，遍历开始时冻结；null 表示无活动遍历。 */
    private Long upperId;
    private boolean traversalHadFailure;
    private final BuyerCatalogSweepFailureLog failureLog = new BuyerCatalogSweepFailureLog();

    /**
     * 执行一批扫描；单线程调度（Task 7 生命周期）调用，实例状态不跨线程共享。
     * 查询失败不推进位置直接上抛，由调度器下一次重试同一批；无候选返回空结果且不结束调度。
     */
    public SweepBatch sweepOneBatch() {
        if (Thread.currentThread().isInterrupted()) {
            // 停止前不启动新条目：中断时不推进、不刷新，返回空结果不伪造成功。
            return new SweepBatch(0, 0, 0, 0);
        }
        if (upperId == null) {
            beginTraversal();
        }
        if (upperId == null) {
            failureLog.recovered(log, BuyerCatalogSweepFailureLog.Stage.REFRESH);
            return new SweepBatch(0, 0, 0, 0);
        }
        List<Long> ids = listingMapper.selectAutoSweepableIds(cursor, upperId, effectiveBatchSize());
        if (ids.isEmpty()) {
            finishTraversal();
            return new SweepBatch(0, 0, 0, 0);
        }
        SweepBatch batch = new SweepBatch(0, 0, 0, 0);
        for (Long id : ids) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            SweepBatch entry = attemptOne(id);
            traversalHadFailure |= entry.failed() > 0;
            batch = batch.plus(entry);
            cursor = id;
        }
        if (cursor != null && cursor >= upperId) {
            finishTraversal();
        }
        return batch;
    }

    /** 遍历开始：读当前最小符合条件的最大主键冻结上界，cursor 从空开始（即从最小ID起）。 */
    private void beginTraversal() {
        Long max = listingMapper.selectMaxAutoSweepableId();
        if (max == null) {
            upperId = null;
            cursor = null;
            return;
        }
        upperId = max;
        cursor = null;
        traversalHadFailure = false;
    }

    /** 到达上界或空页清空位置：下次从最小ID重新读上界，持续新增的高ID下一遍历覆盖。 */
    private void finishTraversal() {
        if (!traversalHadFailure && !Thread.currentThread().isInterrupted()) {
            failureLog.recovered(log, BuyerCatalogSweepFailureLog.Stage.REFRESH);
        }
        upperId = null;
        cursor = null;
    }

    private SweepBatch attemptOne(Long listingId) {
        try {
            BuyerSelectionCatalogRefreshResult result = refreshService.refresh(listingId,
                    BuyerSelectionCatalogRefreshService.Trigger.SWEEP, null);
            if (result.refreshOutcome() == BuyerCatalogRefreshOutcome.UPDATED) {
                return new SweepBatch(1, 1, 0, 0);
            }
            return new SweepBatch(1, 0, 0, 1);
        } catch (RuntimeException failure) {
            // 每条独立刷新事务回滚后继续其余条；可恢复失败由下一次遍历覆盖，不阻断本批。
            failureLog.failure(log, BuyerCatalogSweepFailureLog.Stage.REFRESH, listingId, failure);
            return new SweepBatch(1, 0, 1, 0);
        }
    }

    private int effectiveBatchSize() {
        Integer configured = properties.getSweepBatchSize();
        return configured == null || configured <= 0 ? 100 : configured;
    }
}
