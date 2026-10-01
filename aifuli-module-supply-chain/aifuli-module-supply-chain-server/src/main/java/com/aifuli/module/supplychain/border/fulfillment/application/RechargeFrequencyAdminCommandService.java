package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.framework.common.exception.ErrorCode;
import com.aifuli.framework.common.util.monitor.TracerUtils;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.*;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.*;
import com.aifuli.module.supplychain.border.fulfillment.domain.*;
import com.aifuli.module.supplychain.border.order.domain.SupplierOrderOperationStatus;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.*;

/**
 * 受控重试/释放编排：QUEUE→ENTRY→Request/Order→全部相关BOrder/Operation，资金事实仅由既有生命周期建立。
 * 成功变更与追加审计同一事务；明确业务拒绝先回滚，再REQUIRES_NEW追加；唯一键竞争必须先退出失败事务后回放。
 * 未确定的存储异常向外传播，不伪装为业务拒绝；自由原因只进入内存指纹和脱敏审计。
 * 设计见 {@code docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#7-管理端契约与权限}。
 */
@Service @Slf4j
public class RechargeFrequencyAdminCommandService {
    private static final Pattern ACTION_NO=Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern AUDIT_KEY=Pattern.compile("for key ['`](?:[^'`]*\\.)?uk_sc_recharge_frequency_action_no['`]");
    private final RechargeFrequencyQueueMapper queues;
    private final RechargeFrequencyEntryMapper entries;
    private final RechargeFrequencyLifecycle lifecycle;
    private final RechargeFrequencyWakeScheduler scheduler;
    private final RechargeFrequencyActionAuditService auditService;
    private final SupplyChainDatabaseClock clock;
    private final TransactionTemplate fresh;

    public RechargeFrequencyAdminCommandService(RechargeFrequencyQueueMapper queues,RechargeFrequencyEntryMapper entries,
            RechargeFrequencyLifecycle lifecycle,RechargeFrequencyWakeScheduler scheduler,RechargeFrequencyActionAuditService auditService,
            SupplyChainDatabaseClock clock,@Qualifier("supplyChainTransactionManager") PlatformTransactionManager manager) {
        this.queues=queues; this.entries=entries; this.lifecycle=lifecycle; this.scheduler=scheduler; this.auditService=auditService; this.clock=clock;
        fresh=new TransactionTemplate(manager); fresh.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        fresh.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    /** 只触发原队列普通检查，CAS WAITING项版本并持久化新的Timer代次，不插队或新建外部操作。 */
    public ActionResult retry(Long actor,Long entryId,RetryCommand command) {
        validate(actor,entryId,command.actionNo(),command.expectedVersion(),command.reason());
        log.info("[限频管理重试] actor={}, entryId={}, expectedVersion={}, traceId={}",actor,entryId,command.expectedVersion(),TracerUtils.getTraceId());
        var audit=newAudit(actor,ActionType.RETRY,command.actionNo(),null,command.expectedVersion(),command.reason(),
                new Object[]{actor,ActionType.RETRY.name(),entryId,command.actionNo(),command.expectedVersion(),command.reason()});
        return execute(audit,()-> {
            var locator=entries.selectById(entryId);
            if(locator==null) { throw reject(FREQUENCY_RESOURCE_NOT_FOUND); }
            var queue=queues.selectByIdForUpdate(locator.getQueueId()); var entry=entries.selectByIdForUpdate(entryId);
            if(queue==null || entry==null) { throw reject(FREQUENCY_RESOURCE_NOT_FOUND); }
            if(!Objects.equals(entry.getQueueId(),queue.getId())) { throw new IllegalStateException("限频项队列关联变化"); }
            attach(audit,queue,entry);
            if(!Objects.equals(entry.getVersion(),command.expectedVersion())) { throw reject(FREQUENCY_VERSION_CONFLICT); }
            if(entry.getStatus()!=RechargeFrequencyEntryStatus.WAITING.getCode() || queue.getBlockedReason()!=null) { throw reject(FREQUENCY_STATE_INVALID); }
            entry.setUpdatedAt(clock.now());
            if(entries.updateById(entry)!=1) { throw reject(FREQUENCY_VERSION_CONFLICT); }
            queue.setNextCheckAt(clock.now()); queue.setWakeGeneration(Math.incrementExact(queue.getWakeGeneration())); queue.setUpdatedAt(clock.now());
            if(queues.updateById(queue)!=1) { throw reject(FREQUENCY_VERSION_CONFLICT); }
            scheduler.schedule(queue); audit.setAfterStateJson(snapshot(queue,entry)); return ActionCode.RETRY_SCHEDULED;
        });
    }
    /**
     * 持有者/代次/版本全匹配才验证可信终态或撤销PREPARED；SEND_POSSIBLE/UNKNOWN/MANUAL均不可由运营勾选解除。
     * success使用原acquiredAt+5秒，不按运营请求时间重新开窗；撤销保留原FIFO和资金。
     */
    public ActionResult release(Long actor,Long queueId,ReleaseCommand command) {
        validate(actor,queueId,command.actionNo(),command.expectedVersion(),command.reason());
        if(command.expectedGeneration()==null || command.expectedGeneration()<0 || command.expectedHolderOrderNo()==null
                || command.expectedHolderOrderNo().isBlank() || command.expectedHolderOrderNo().length()>64) { throw exception(FREQUENCY_PARAMETER_INVALID); }
        log.info("[限频管理释放] actor={}, queueId={}, expectedGeneration={}, expectedVersion={}, traceId={}",
                actor,queueId,command.expectedGeneration(),command.expectedVersion(),TracerUtils.getTraceId());
        var audit=newAudit(actor,ActionType.RELEASE,command.actionNo(),command.expectedGeneration(),command.expectedVersion(),command.reason(),
                new Object[]{actor,ActionType.RELEASE.name(),queueId,command.actionNo(),command.expectedHolderOrderNo(),command.expectedGeneration(),command.expectedVersion(),command.reason()});
        return execute(audit,()-> {
            var queue=queues.selectByIdForUpdate(queueId);
            if(queue==null) { throw reject(FREQUENCY_RESOURCE_NOT_FOUND); }
            var entry=queue.getHolderEntryId()==null ? null : entries.selectByIdForUpdate(queue.getHolderEntryId());
            attach(audit,queue,entry);
            if(!Objects.equals(queue.getVersion(),command.expectedVersion()) || !Objects.equals(queue.getOccupancyGeneration(),command.expectedGeneration())
                    || entry==null || !Objects.equals(entry.getOrderNo(),command.expectedHolderOrderNo())) { throw reject(FREQUENCY_VERSION_CONFLICT); }
            if(queue.getBlockedReason()!=null) { throw reject(FREQUENCY_RELEASE_UNSAFE); }
            var locked=lifecycle.lockForResult(entry.getActiveOperationNo());
            if(!RechargeFrequencyLifecycle.isCurrentHolder(locked) || !Objects.equals(locked.queue().getId(),queueId)) { throw reject(FREQUENCY_RELEASE_UNSAFE); }
            attach(audit,locked.queue(),locked.entry());
            boolean success=locked.request().getStatus()==FulfillmentRequestStatus.SUCCEEDED.getCode()
                    && locked.operation().getStatus()==SupplierOrderOperationStatus.SUCCEEDED.getCode()
                    && OperationOutcomeClass.SUCCEEDED.name().equals(locked.operation().getResultClass());
            boolean failed=locked.request().getStatus()==FulfillmentRequestStatus.FAILED.getCode()
                    && locked.operation().getStatus()==SupplierOrderOperationStatus.FAILED.getCode()
                    && OperationOutcomeClass.REJECTED.name().equals(locked.operation().getResultClass());
            if(success || failed) {
                if(success && (locked.queue().getAcquiredAt()==null || !RechargeFrequencyPolicy.canReleaseSuccess(locked.queue().getAcquiredAt(),clock.now()))) {
                    throw reject(FREQUENCY_SUCCESS_WINDOW);
                }
                lifecycle.reconcile(locked);
                if(locked.queue().getHolderEntryId()!=null) { throw reject(FREQUENCY_RELEASE_UNSAFE); }
            } else {
                if (locked.request().getStatus()!=FulfillmentRequestStatus.FULFILLING.getCode()) { throw reject(FREQUENCY_RELEASE_UNSAFE); }
                var permit=new RechargeFrequencyExecutionPermit(locked.queue().getId(),locked.entry().getId(),locked.operation().getOperationNo(),
                        locked.queue().getOccupancyGeneration(),locked.entry().getVersion(),locked.operation().getVersion());
                if(!lifecycle.revokePrepared(permit)) { throw reject(FREQUENCY_RELEASE_UNSAFE); }
            }
            var afterQueue=queues.selectById(queueId); var afterEntry=entries.selectById(entry.getId());
            audit.setAfterStateJson(snapshot(afterQueue,afterEntry)); return ActionCode.RELEASED;
        });
    }
    /** 回放、成功变更、明确拒绝与唯一键竞争采用分开的新事务；catch在模板回滚结束后运行。 */
    private ActionResult execute(RechargeFrequencyActionAuditDO audit,Supplier<ActionCode> mutation) {
        var original=fresh.execute(status->auditService.find(audit.getActionNo()));
        if(original!=null) { return replay(original,audit); }
        try {
            return fresh.execute(status->{
                // 再读只是减少重复工作；唯一约束决定并发请求唯一赢家，竞争者全部变更必须回滚。
                var existing=auditService.find(audit.getActionNo());
                if(existing!=null) { return replay(existing,audit); }
                var code=mutation.get(); audit.setResultCode(code.name()); auditService.append(audit);
                return result(audit);
            });
        } catch(BusinessReject rejection) {
            audit.setResultCode(Integer.toString(rejection.error.getCode())); audit.setAfterStateJson(audit.getBeforeStateJson());
            try { fresh.executeWithoutResult(status->auditService.append(audit)); }
            catch(DuplicateKeyException competition) {
                if(!isAuditCompetition(competition)) { throw auditFailure(rejection,competition); }
                return replayAfterRollback(audit,competition);
            } catch(RuntimeException storage) { throw auditFailure(rejection,storage); }
            throw exception(rejection.error);
        } catch(DuplicateKeyException competition) {
            if(!isAuditCompetition(competition)) { throw competition; }
            return replayAfterRollback(audit,competition);
        }
    }
    private ActionResult replayAfterRollback(RechargeFrequencyActionAuditDO command,RuntimeException originalFailure) {
        var original=fresh.execute(status->auditService.find(command.getActionNo()));
        if(original==null) { throw new IllegalStateException("限频审计竞争原结果缺失",originalFailure); }
        return replay(original,command);
    }
    private ActionResult replay(RechargeFrequencyActionAuditDO original,RechargeFrequencyActionAuditDO command) {
        if(!java.security.MessageDigest.isEqual(original.getRequestFingerprint(),command.getRequestFingerprint())) {
            log.warn("[限频管理幂等冲突] actor={}, actionType={}, traceId={}",command.getOperatorUserId(),command.getActionType(),TracerUtils.getTraceId());
            throw exception(FREQUENCY_ACTION_CONFLICT);
        }
        return result(original);
    }
    private ActionResult result(RechargeFrequencyActionAuditDO audit) {
        for(var code:ActionCode.values()) {
            if(code.name().equals(audit.getResultCode())) { return new ActionResult(audit.getActionNo(),code.name(),code.message); }
        }
        for(var error:new ErrorCode[]{FREQUENCY_RESOURCE_NOT_FOUND,FREQUENCY_VERSION_CONFLICT,FREQUENCY_STATE_INVALID,FREQUENCY_RELEASE_UNSAFE,FREQUENCY_SUCCESS_WINDOW}) {
            if(Integer.toString(error.getCode()).equals(audit.getResultCode())) { throw exception(error); }
        }
        throw new IllegalStateException("限频审计结果码非法");
    }
    private RechargeFrequencyActionAuditDO newAudit(Long actor,ActionType type,String actionNo,Long generation,Long version,String reason,Object[] fields) {
        return RechargeFrequencyActionAuditDO.builder().actionNo(actionNo).requestFingerprint(auditService.fingerprint(fields))
                .actionType(type.name()).expectedGeneration(generation).expectedVersion(version).operatorUserId(actor).reason(reason)
                .traceId(Objects.toString(TracerUtils.getTraceId(),"")).build();
    }
    private void attach(RechargeFrequencyActionAuditDO audit,RechargeFrequencyQueueDO queue,RechargeFrequencyEntryDO entry) {
        audit.setQueueId(queue.getId()); audit.setEntryId(entry==null ? null : entry.getId()); audit.setOrderNo(entry==null ? null : entry.getOrderNo());
        audit.setBeforeStateJson(snapshot(queue,entry));
    }
    private String snapshot(RechargeFrequencyQueueDO queue,RechargeFrequencyEntryDO entry) {
        var safe=new LinkedHashMap<String,Object>();
        safe.put("queueId",queue.getId()); safe.put("queueVersion",queue.getVersion()); safe.put("generation",queue.getOccupancyGeneration());
        safe.put("holderEntryId",queue.getHolderEntryId()); safe.put("wakeGeneration",queue.getWakeGeneration()); safe.put("blocked",queue.getBlockedReason()!=null);
        if(entry!=null) { safe.put("entryId",entry.getId()); safe.put("entryVersion",entry.getVersion()); safe.put("status",entry.getStatus()); safe.put("queueSequence",entry.getEnqueueSequence()); safe.put("dispatchPhase",entry.getDispatchPhase()); }
        return auditService.snapshot(safe);
    }
    private static void validate(Long actor,Long resource,String actionNo,Long version,String reason) {
        if(actor==null || actor<=0) { throw new org.springframework.security.access.AccessDeniedException("内部运营身份必需"); }
        if(resource==null || resource<=0 || actionNo==null || !ACTION_NO.matcher(actionNo).matches() || version==null || version<0
                || reason==null || reason.isBlank() || reason.length()>512) { throw exception(FREQUENCY_PARAMETER_INVALID); }
    }
    private static BusinessReject reject(ErrorCode error) { return new BusinessReject(error); }
    private static IllegalStateException auditFailure(BusinessReject rejection,RuntimeException storage) {
        var failure=new IllegalStateException("限频业务拒绝审计持久化失败",storage); failure.addSuppressed(rejection); return failure;
    }
    static boolean isAuditCompetition(Throwable ex) {
        for(Throwable current=ex;current!=null;current=current.getCause()) {
            if(current instanceof SQLException sql && sql.getErrorCode()==1062 && "23000".equals(sql.getSQLState())
                    && sql.getMessage()!=null && AUDIT_KEY.matcher(sql.getMessage()).find()) { return true; }
        }
        return false;
    }
    private static final class BusinessReject extends RuntimeException {
        private final ErrorCode error;
        BusinessReject(ErrorCode error) { super(error.getMsg()); this.error=error; }
    }
    private enum ActionType { RETRY, RELEASE }
    private enum ActionCode {
        RETRY_SCHEDULED("已触发检查"), RELEASED("已释放占用");
        private final String message; ActionCode(String message) {this.message=message;}
    }
    /** 管理命令禁止自动toString打印原始原因。 */
    public record RetryCommand(
            /** 管理操作幂等号。 */ String actionNo,
            /** 请求预期的乐观锁版本。 */ Long expectedVersion,
            /** 管理操作原因。 */ String reason) { @Override public String toString(){return "RetryCommand[expectedVersion="+expectedVersion+"]";} }
    public record ReleaseCommand(
            /** 管理操作幂等号。 */ String actionNo,
            /** 供应链总订单号。 */ String expectedHolderOrderNo,
            /** 请求预期的占用代次。 */ Long expectedGeneration,
            /** 请求预期的乐观锁版本。 */ Long expectedVersion,
            /** 管理操作原因。 */ String reason) { @Override public String toString(){return "ReleaseCommand[expectedGeneration="+expectedGeneration+",expectedVersion="+expectedVersion+"]";} }
    /** 管理操作确定结果，重复同参数返回原结果，不承诺执行已发送。 */
    public record ActionResult(
            /** 管理操作幂等号。 */ String actionNo,
            /** 管理操作结果码。 */ String resultCode,
            /** 确定结果说明；触发检查不承诺已发送。 */ String message) { }
}
