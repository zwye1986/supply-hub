package com.aifuli.module.supplychain.border.fulfillment.application;

import static com.aifuli.module.supplychain.border.fulfillment.domain.RechargeFrequencyMigrationCodes.*;

import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.BOrderDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentCallLogDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentOperationDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentRequestDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.RawStatusObservationDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.RechargeFrequencyEntryDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.RechargeFrequencyQueueDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.BOrderMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentAcceptanceReceiptMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentCallLogMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentOperationMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentRequestMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RawStatusObservationMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyEntryMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyQueueMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.AdapterStepCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOperationKind;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentRequestStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTargetFields;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcome;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcomeClass;
import com.aifuli.module.supplychain.border.fulfillment.domain.RechargeFrequencyConstants;
import com.aifuli.module.supplychain.border.fulfillment.domain.RechargeFrequencyDispatchPhase;
import com.aifuli.module.supplychain.border.fulfillment.domain.RechargeFrequencyEntryStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.RechargeFrequencyMigrationCodes;
import com.aifuli.module.supplychain.border.fulfillment.domain.RechargeFrequencyMigrationTimeBasis;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.SupplierOrderOperationStatus;
import com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderTypes;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.infrastructure.security.RechargeFrequencyIdentityKeyProvider;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.catalog.application.RechargeFrequencyProductResolver;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * 仅供附加Spring配置的受控离线部署流程使用，不提供HTTP、Timer或运营force入口。 全部候选可信身份、关联、风险和顺序预检完成后才写队列；不新建操作、发送或改变资金。
 * 部署证据缺失默认失败关闭，普通供应链及其余独立进程启动不因此增加部署检查。 设计见
 * docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#101-迁移执行与核实边界。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RechargeFrequencyMigrationService {
    public static final String TARGET_VERSION_PROPERTY =
            "aifuli.supply-chain.recharge-frequency.migration-target-version";
    private final FulfillmentRequestMapper requests;
    private final SupplyChainOrderMapper orders;
    private final BOrderMapper bOrders;
    private final FulfillmentOperationMapper operations;
    private final FulfillmentCallLogMapper calls;
    private final RawStatusObservationMapper observations;
    private final FulfillmentAcceptanceReceiptMapper receipts;
    private final RechargeFrequencyQueueMapper queues;
    private final RechargeFrequencyEntryMapper entries;
    private final EnvelopeCryptoPort crypto;
    private final ObjectMapper json;
    private final FulfillmentTargetSchemaRegistry schemaRegistry;
    private final RechargeFrequencyProductResolver products;
    private final RechargeFrequencyIdentityService identities;
    private final RechargeFrequencyIdentityKeyProvider keyProvider;
    private final SupplyChainDatabaseClock clock;
    private final RechargeFrequencyWakeScheduler scheduler;
    private final SupplierOrderLifecycle supplierLifecycle;
    private final RechargeFrequencyLifecycle lifecycle;
    private final ObjectProvider<DeploymentQuiescenceEvidencePort> deploymentEvidence;
    private final Environment environment;
    private final RechargeFrequencyMigrationGateService migrationGate;
    private final StandardOrderIdentityMigrationService standardOrderIdentityMigration;

    /**
     * 离线完整预检及原子迁移；证据在预检前和写入前重新核验，任何失败均不产生部分队列写入。 已冻结项优先复用，历史展示账号、buyerUserId、普通审计时间均不得作为身份或发送证据。
     * 标准订单身份离线覆盖（052 后）在门禁关闭后、候选预检前同事务执行：回执格式 2 重建与
     * 限购手机号快照覆盖、限频队列写入与最终 READY 同事务提交，任意失败全回滚。
     * 设计见
     * docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#101-迁移执行与核实边界、
     * docs/superpowers/specs/2026-10-02-standard-acceptance-project-key-design.md#51-离线应用迁移与实际手机号覆盖。
     */
    @Transactional(
            transactionManager = "supplyChainTransactionManager",
            isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class)
    public MigrationReport migrate() {
        try {
            return migrateInternal();
        } catch (MigrationException exception) {
            throw exception;
        } catch (StandardOrderIdentityMigrationService.MigrationException exception) {
            // 标准订单身份离线覆盖的安全错误（关联断裂/解密失败/快照矛盾）原样透传：
            // 保留单号与安全错误码供离线核实，不得被 STORAGE_FAILURE 掩盖语义。
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(null, STORAGE_FAILURE, exception);
        }
    }

    private MigrationReport migrateInternal() {
        assertDeploymentEvidence();
        if (!migrationGate.closeBeforePreflight()) {
            throw failure(null, IDENTITY_KEY_INVALID);
        }
        boolean verifiedIdentityBinding = migrationGate.hasVerifiedIdentityBinding();
        // 052 后标准订单身份离线覆盖先于限频候选预检：同一供应链事务，任意解密/审计/关联失败
        // 全批回滚且门禁保持已提交 CLOSED；幂等重跑只校验已重建绑定后跳过。
        var identityReport = standardOrderIdentityMigration.migrateIdentitySnapshots();
        log.info("[直充限频迁移] 标准订单身份离线覆盖完成 rebuilt={}, rebuiltSkipped={}, unverifiable={}",
                identityReport.rebuiltCount(), identityReport.rebuiltSkipped(),
                identityReport.unverifiableSkipped());
        log.info("[直充限频迁移] 开始全候选预检");
        var now = clock.now();
        var candidates = new ArrayList<Candidate>();
        int reused = 0;
        var seenOrders = new HashSet<Long>();
        for (var request :
                requests.selectList(
                        new LambdaQueryWrapper<FulfillmentRequestDO>()
                                .orderByAsc(FulfillmentRequestDO::getId))) {
            if (!seenOrders.add(request.getOrderId())) {
                throw failure(null, ASSOCIATION_INVALID);
            }
            var frozen = entries.selectByOrderId(request.getOrderId());
            if (frozen != null) {
                validateFrozenState(request, frozen);
                if (!Objects.equals(frozen.getFulfillmentRequestId(), request.getId())
                        || queues.selectById(frozen.getQueueId()) == null) {
                    throw failure(frozen.getOrderNo(), ASSOCIATION_INVALID);
                }
                if (!verifiedIdentityBinding) {
                    var originalOrder = orders.selectById(request.getOrderId());
                    if (originalOrder == null
                            || !Objects.equals(originalOrder.getBuyerId(), request.getBuyerId())
                            || !Objects.equals(frozen.getBuyerId(), originalOrder.getBuyerId())) {
                        throw failure(frozen.getOrderNo(), ASSOCIATION_INVALID);
                    }
                    var identity = recoverTargetIdentity(request, originalOrder);
                    var queue = queues.selectById(frozen.getQueueId());
                    if (identity == null
                            || !MessageDigest.isEqual(
                                    identity.mobileDigest(), queue.getMobileDigest())) {
                        throw failure(frozen.getOrderNo(), IDENTITY_KEY_INVALID);
                    }
                }
                reused++;
                continue;
            }
            Candidate candidate = preflight(request, now);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        // 覆盖总单缺Request等孤儿关联，不能仅扫描Request而把遗漏历史单标成READY。
        for (var order :
                orders.selectList(
                        new LambdaQueryWrapper<SupplyChainOrderDO>()
                                .eq(
                                        SupplyChainOrderDO::getOrderType,
                                        SupplyChainOrderTypes.ORDER_TYPE_CARD))) {
            if (!seenOrders.contains(order.getId())) {
                throw failure(order.getOrderNo(), ASSOCIATION_INVALID);
            }
        }
        candidates.sort(
                Comparator.comparing(Candidate::acceptedAt).thenComparing(c -> c.order().getId()));
        var groups = new LinkedHashMap<String, List<Candidate>>();
        for (var candidate : candidates) {
            String identity =
                    HexFormat.of().formatHex(candidate.identity().mobileDigest())
                            + ":"
                            + candidate.spuId();
            groups.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(candidate);
        }
        // 在任何INSERT之前检查已有冻结序号的顺序缺口以及当前占用不能被迁移覆盖。
        for (var group : groups.values()) {
            var first = group.getFirst();
            var queue = queues.selectByIdentity(first.identity().mobileDigest(), first.spuId());
            if (queue == null) {
                continue;
            }
            var frozen =
                    entries.selectList(
                            new LambdaQueryWrapper<RechargeFrequencyEntryDO>()
                                    .eq(RechargeFrequencyEntryDO::getQueueId, queue.getId())
                                    .orderByAsc(RechargeFrequencyEntryDO::getEnqueueSequence));
            for (var candidate : group) {
                if (frozen.stream()
                        .anyMatch(
                                e ->
                                        e.getEnqueuedAt() == null
                                                || candidate
                                                        .acceptedAt()
                                                        .isBefore(e.getEnqueuedAt())
                                                || (candidate.acceptedAt().equals(e.getEnqueuedAt())
                                                        && candidate.order().getId()
                                                                < e.getOrderId()))) {
                    throw failure(candidate.order().getOrderNo(), FIFO_GAP);
                }
            }
            if (group.stream().anyMatch(Candidate::risk)
                    && (queue.getHolderEntryId() != null || queue.getBlockedReason() != null)) {
                throw failure(first.order().getOrderNo(), UNRESOLVED_RISK);
            }
        }
        assertDeploymentEvidence();
        for (var group : groups.values()) {
            persist(group, now);
        }
        assertDeploymentEvidence();
        migrationGate.publishReady(environment.getProperty(TARGET_VERSION_PROPERTY));
        log.info("[直充限频迁移] 全批完成 migratedCount={}, reusedCount={}", candidates.size(), reused);
        return new MigrationReport(candidates.size(), reused);
    }

    /**
     * 已有可信密钥绑定也必须核验原关联和状态，不把冻结项存在解释为候选覆盖完成。 只读取原Request/Order/BO/Op及发送证据；不解析可变SKU、不重算身份或修改冻结序号。 设计见
     * docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#101-迁移执行与核实边界。
     */
    /**
     * 复用冻结项前核实原Order/Request及全部充值操作，要求未决风险唯一且归属于activeOperation。
     * 合法未发continuation可保持原身份、SPU和FIFO序号；其余操作的未知风险必须全批拒绝。 设计见
     * docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#101-迁移执行与核实边界。
     */
    private void validateFrozenState(FulfillmentRequestDO request, RechargeFrequencyEntryDO entry) {
        String orderNo = entry.getOrderNo();
        var order = orders.selectById(request.getOrderId());
        var queue = queues.selectById(entry.getQueueId());
        var operation =
                operations.selectOne(
                        new LambdaQueryWrapper<FulfillmentOperationDO>()
                                .eq(
                                        FulfillmentOperationDO::getOperationNo,
                                        entry.getActiveOperationNo()));
        var bo = operation == null ? null : bOrders.selectById(operation.getBOrderId());
        if (order == null
                || queue == null
                || operation == null
                || bo == null
                || !Objects.equals(entry.getFulfillmentRequestId(), request.getId())
                || !Objects.equals(entry.getOrderId(), order.getId())
                || !Objects.equals(entry.getOrderNo(), order.getOrderNo())
                || !Objects.equals(order.getBuyerId(), request.getBuyerId())
                || !Objects.equals(entry.getBuyerId(), order.getBuyerId())
                || !Objects.equals(entry.getProjectId(), order.getProjectId())
                || !Objects.equals(order.getDetailId(), request.getId())
                || !Objects.equals(bo.getOrderId(), order.getId())
                || !Objects.equals(bo.getFulfillmentRequestId(), request.getId())
                || !isRecharge(operation)) {
            throw failure(orderNo, ASSOCIATION_INVALID);
        }
        var bos =
                bOrders.selectList(
                        new LambdaQueryWrapper<BOrderDO>().eq(BOrderDO::getOrderId, order.getId()));
        if (bos.stream()
                .anyMatch(row -> !Objects.equals(row.getFulfillmentRequestId(), request.getId()))) {
            throw failure(orderNo, ASSOCIATION_INVALID);
        }
        var chargeOps =
                operations
                        .selectList(
                                new LambdaQueryWrapper<FulfillmentOperationDO>()
                                        .in(
                                                FulfillmentOperationDO::getBOrderId,
                                                bos.stream().map(BOrderDO::getId).toList()))
                        .stream()
                        .filter(RechargeFrequencyMigrationService::isRecharge)
                        .toList();
        var unresolvedRisks =
                chargeOps.stream()
                        .filter(op -> !trustedTerminal(op) && !certainlyUnsent(op))
                        .toList();
        long risks = unresolvedRisks.size();
        // 未核实风险只能属于冻结当前Op且最多一个；不能由PREPARED continuation或成功窗口隐藏其他风险。
        // Long主键按数值归属比较。可信终态当前Op不会进入风险集合，故任意剩余风险同样拒绝。
        if (risks > 1
                || unresolvedRisks.stream()
                        .anyMatch(op -> !Objects.equals(op.getId(), operation.getId()))) {
            throw failure(orderNo, MULTIPLE_UNVERIFIED_OPERATIONS);
        }
        boolean success =
                operation.getStatus() != null
                        && operation.getStatus()
                                == SupplierOrderOperationStatus.SUCCEEDED.getCode();
        boolean consistentTerminal =
                trustedTerminal(operation)
                        && Objects.equals(
                                request.getStatus(),
                                (success
                                                ? FulfillmentRequestStatus.SUCCEEDED
                                                : FulfillmentRequestStatus.FAILED)
                                        .getCode())
                        && Objects.equals(
                                order.getOrderStatus(),
                                (success
                                                ? SupplyChainOrderStatus.SUCCEEDED
                                                : SupplyChainOrderStatus.FAILED)
                                        .getCode());
        if (entry.getStatus() == RechargeFrequencyEntryStatus.FINISHED.getCode()) {
            if (!consistentTerminal || risks != 0) {
                throw failure(orderNo, STATE_CONTRADICTION);
            }
            return;
        }
        if (entry.getStatus() == RechargeFrequencyEntryStatus.WAITING.getCode()) {
            if (!certainlyUnsent(operation)
                    || risks != 0
                    || !Objects.equals(
                            request.getStatus(), FulfillmentRequestStatus.FULFILLING.getCode())
                    || !Objects.equals(
                            order.getOrderStatus(), SupplyChainOrderStatus.PROCESSING.getCode())) {
                throw failure(orderNo, STATE_CONTRADICTION);
            }
            return;
        }
        if (entry.getStatus() != RechargeFrequencyEntryStatus.HOLDING.getCode()
                || !Objects.equals(queue.getOccupancyGeneration(), entry.getOccupancyGeneration())
                || (queue.getHolderEntryId() == null
                        && (!MIGRATION_CONFLICT.name().equals(queue.getBlockedReason())
                                || entry.getMigrationAcquiredAt() == null
                                || entry.getMigrationTimeBasis() == null))
                || (queue.getHolderEntryId() != null
                        && !Objects.equals(queue.getHolderEntryId(), entry.getId()))
                || (trustedTerminal(operation) && !consistentTerminal)
                || (!trustedTerminal(operation)
                        && (request.getStatus() == null
                                || order.getOrderStatus() == null
                                || (request.getStatus()
                                                != FulfillmentRequestStatus.FULFILLING.getCode()
                                        && request.getStatus()
                                                != FulfillmentRequestStatus.MANUAL.getCode())
                                || (order.getOrderStatus()
                                                != SupplyChainOrderStatus.PROCESSING.getCode()
                                        && order.getOrderStatus()
                                                != SupplyChainOrderStatus.MANUAL.getCode())))) {
            throw failure(orderNo, STATE_CONTRADICTION);
        }
    }

    private Candidate preflight(FulfillmentRequestDO request, LocalDateTime now) {
        var order = orders.selectById(request.getOrderId());
        String orderNo = order == null ? null : order.getOrderNo();
        if (order == null
                || !Objects.equals(order.getBuyerId(), request.getBuyerId())
                || !Objects.equals(order.getPlatformSkuId(), request.getPlatformSkuId())
                || !SupplyChainOrderTypes.ORDER_TYPE_CARD.equals(order.getOrderType())
                || !SupplyChainOrderTypes.DETAIL_TYPE_FULFILLMENT.equals(order.getDetailType())
                || !Objects.equals(order.getDetailId(), request.getId())) {
            throw failure(orderNo, ASSOCIATION_INVALID);
        }
        RechargeFrequencyProductResolver.ProductIdentity product;
        try {
            product = products.resolve(request.getPlatformSkuId());
        } catch (RuntimeException exception) {
            throw failure(orderNo, PRODUCT_UNRECOVERABLE, exception);
        }
        if (!product.directRecharge()) {
            return null;
        }
        var identity = recoverTargetIdentity(request, order);
        if (identity == null) {
            return null;
        }
        var bos =
                bOrders.selectList(
                        new LambdaQueryWrapper<BOrderDO>().eq(BOrderDO::getOrderId, order.getId()));
        if (bos.isEmpty()
                || bos.stream()
                        .anyMatch(
                                b ->
                                        !Objects.equals(
                                                b.getFulfillmentRequestId(), request.getId()))) {
            throw failure(orderNo, ASSOCIATION_INVALID);
        }
        var ops =
                operations.selectList(
                        new LambdaQueryWrapper<FulfillmentOperationDO>()
                                .in(
                                        FulfillmentOperationDO::getBOrderId,
                                        bos.stream().map(BOrderDO::getId).toList()));
        var chargeOps = ops.stream().filter(RechargeFrequencyMigrationService::isRecharge).toList();
        if (chargeOps.isEmpty()) {
            throw failure(orderNo, ASSOCIATION_INVALID);
        }
        var unresolved = chargeOps.stream().filter(op -> !trustedTerminal(op)).toList();
        if (unresolved.isEmpty()) {
            boolean success =
                    chargeOps.stream()
                            .anyMatch(
                                    op ->
                                            op.getStatus()
                                                    == SupplierOrderOperationStatus.SUCCEEDED
                                                            .getCode());
            if ((success
                            && request.getStatus() != null
                            && request.getStatus() == FulfillmentRequestStatus.SUCCEEDED.getCode()
                            && order.getOrderStatus() == SupplyChainOrderStatus.SUCCEEDED.getCode())
                    || (!success
                            && request.getStatus() != null
                            && request.getStatus() == FulfillmentRequestStatus.FAILED.getCode()
                            && order.getOrderStatus() == SupplyChainOrderStatus.FAILED.getCode())) {
                return null;
            }
            throw failure(orderNo, STATE_CONTRADICTION);
        }
        if (order.getOrderStatus() == null
                || (order.getOrderStatus() != SupplyChainOrderStatus.PROCESSING.getCode()
                        && order.getOrderStatus() != SupplyChainOrderStatus.MANUAL.getCode())
                || request.getStatus() == null
                || request.getStatus() == FulfillmentRequestStatus.SUCCEEDED.getCode()
                || request.getStatus() == FulfillmentRequestStatus.FAILED.getCode()
                || chargeOps.stream()
                        .anyMatch(
                                op ->
                                        op.getStatus() != null
                                                && op.getStatus()
                                                        == SupplierOrderOperationStatus.SUCCEEDED
                                                                .getCode())) {
            throw failure(orderNo, STATE_CONTRADICTION);
        }
        var risks = unresolved.stream().filter(op -> !certainlyUnsent(op)).toList();
        if (risks.size() > 1) {
            throw failure(orderNo, MULTIPLE_UNVERIFIED_OPERATIONS);
        }
        FulfillmentOperationDO active;
        if (!risks.isEmpty()) {
            active = risks.getFirst();
        } else {
            var activeBos =
                    bos.stream()
                            .filter(
                                    b ->
                                            Objects.equals(b.getId(), request.getActiveBOrderId())
                                                    || (request.getActiveBOrderId() == null
                                                            && Boolean.TRUE.equals(
                                                                    b.getActiveFlag())))
                            .toList();
            if (activeBos.size() != 1) {
                throw failure(orderNo, ASSOCIATION_INVALID);
            }
            active =
                    unresolved.stream()
                            .filter(op -> op.getBOrderId().equals(activeBos.getFirst().getId()))
                            .min(Comparator.comparing(FulfillmentOperationDO::getSequenceNo))
                            .orElseThrow(() -> failure(orderNo, ASSOCIATION_INVALID));
        }
        var activeBo =
                bos.stream()
                        .filter(b -> b.getId().equals(active.getBOrderId()))
                        .findFirst()
                        .orElseThrow(() -> failure(orderNo, ASSOCIATION_INVALID));
        if (request.getActiveBOrderId() != null
                && !Objects.equals(request.getActiveBOrderId(), activeBo.getId())) {
            throw failure(orderNo, STATE_CONTRADICTION);
        }
        if (request.getActiveBOrderId() == null && !Boolean.TRUE.equals(activeBo.getActiveFlag())) {
            throw failure(orderNo, STATE_CONTRADICTION);
        }
        LocalDateTime acceptedAt = order.getCreatedAt();
        if (order.getAcceptanceReceiptId() != null) {
            var receipt = receipts.selectById(order.getAcceptanceReceiptId());
            if (receipt == null
                    || !Objects.equals(receipt.getResultOrderId(), order.getId())
                    || !Objects.equals(receipt.getBuyerId(), order.getBuyerId())
                    || !Objects.equals(receipt.getProjectId(), order.getProjectId())
                    || !Objects.equals(receipt.getBuyerOrderNo(), order.getBuyerOrderNo())
                    || !Objects.equals(receipt.getResultOrderNo(), orderNo)
                    || !Objects.equals(
                            receipt.getResultFulfillmentRequestNo(), request.getRequestNo())
                    || !Objects.equals(receipt.getTargetSchemaCode(), request.getTargetSchemaCode())
                    || receipt.getAcceptedAt() == null) {
                throw failure(orderNo, ACCEPTANCE_TIME_INVALID);
            }
            acceptedAt = receipt.getAcceptedAt();
        }
        if (acceptedAt == null || acceptedAt.isAfter(now)) {
            throw failure(orderNo, ACCEPTANCE_TIME_INVALID);
        }
        // 原CALL方向和endpoint无法证明原SEND，保守冻结本次数据库迁移时间；绝不使用createdAt猜发送时间。
        return new Candidate(
                order,
                request,
                active,
                identity,
                product.platformSpuId(),
                acceptedAt,
                !risks.isEmpty(),
                now);
    }

    /** 原Request密文与原AAD恢复可信target，只用于新身份或首次gate指纹绑定核验，永不重分配冻结SPU/sequence。 */
    private RechargeFrequencyIdentityService.MobileIdentity recoverTargetIdentity(
            FulfillmentRequestDO request, SupplyChainOrderDO order) {
        String orderNo = order.getOrderNo();
        if (request.getTargetSchemaCode() == null
                || request.getTargetSchemaCode().isBlank()
                || request.getTargetSchemaVersion() == null
                || request.getTargetSchemaVersion() <= 0
                || request.getTargetKeyVersion() == null
                || request.getTargetCiphertext() == null
                || request.getTargetCiphertext().length == 0
                || order.getBuyerOrderNo() == null) {
            throw failure(orderNo, TARGET_UNRECOVERABLE);
        }
        RechargeFrequencyIdentityService.MobileIdentity identity;
        try {
            byte[] aad =
                    (request.getBuyerId()
                                    + "|"
                                    + order.getBuyerOrderNo()
                                    + "|"
                                    + request.getTargetSchemaCode()
                                    + "|"
                                    + request.getTargetSchemaVersion())
                            .getBytes(StandardCharsets.UTF_8);
            var target =
                    json.readTree(
                            crypto.decrypt(
                                    request.getTargetCiphertext(),
                                    request.getTargetKeyVersion(),
                                    aad));
            if (!request.getTargetSchemaCode().equals(target.path("schemaCode").asText())
                    || !target.path("schemaVersion").isIntegralNumber()
                    || request.getTargetSchemaVersion() != target.path("schemaVersion").asInt()) {
                throw failure(orderNo, TARGET_SCHEMA_INVALID);
            }
            var values = target.path("values");
            if (!values.isObject()) {
                throw failure(orderNo, TARGET_SCHEMA_INVALID);
            }
            var valueMap = new java.util.HashMap<String, String>();
            values.fields()
                    .forEachRemaining(
                            field -> {
                                if (!field.getValue().isTextual()) {
                                    throw failure(orderNo, TARGET_SCHEMA_INVALID);
                                }
                                valueMap.put(field.getKey(), field.getValue().textValue());
                            });
            schemaRegistry.validate(
                    request.getTargetSchemaCode(), request.getTargetSchemaVersion(), valueMap);
            String type = values.path(FulfillmentTargetFields.ACCOUNT_TYPE).asText(null);
            String account = values.path(FulfillmentTargetFields.ACCOUNT_VALUE).asText(null);
            if (FulfillmentTargetFields.ACCOUNT_TYPE_EXTERNAL_ACCOUNT.equals(type)
                    && account != null
                    && !account.isBlank()) {
                return null;
            }
            if (!FulfillmentTargetFields.ACCOUNT_TYPE_MOBILE.equals(type)) {
                throw failure(orderNo, TARGET_SCHEMA_INVALID);
            }
            identity = identities.identify(account);
        } catch (MigrationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure(orderNo, TARGET_UNRECOVERABLE, exception);
        }
        return identity;
    }

    private boolean certainlyUnsent(FulfillmentOperationDO op) {
        return op.getStatus() != null
                && (op.getStatus() == SupplierOrderOperationStatus.PENDING.getCode()
                        || op.getStatus() == SupplierOrderOperationStatus.CLAIMED.getCode())
                && Objects.equals(op.getAttemptCount(), 0)
                && op.getResultClass() == null
                && op.getExternalOperationNo() == null
                && op.getCompletedAt() == null
                && calls.selectCount(
                                new LambdaQueryWrapper<FulfillmentCallLogDO>()
                                        .eq(FulfillmentCallLogDO::getOperationId, op.getId()))
                        == 0
                && observations.selectCount(
                                new LambdaQueryWrapper<RawStatusObservationDO>()
                                        .eq(RawStatusObservationDO::getOperationId, op.getId()))
                        == 0;
    }

    private static boolean trustedTerminal(FulfillmentOperationDO op) {
        return op.getStatus() != null
                && ((op.getStatus() == SupplierOrderOperationStatus.SUCCEEDED.getCode()
                                && OperationOutcomeClass.SUCCEEDED
                                        .name()
                                        .equals(op.getResultClass()))
                        || (op.getStatus() == SupplierOrderOperationStatus.FAILED.getCode()
                                && OperationOutcomeClass.REJECTED
                                        .name()
                                        .equals(op.getResultClass())));
    }

    private static boolean isRecharge(FulfillmentOperationDO op) {
        return FulfillmentOperationKind.FULFILL.name().equals(op.getOperationKind())
                && (AdapterStepCode.SEND.name().equals(op.getAdapterStepCode())
                        || AdapterStepCode.SEND_KM.name().equals(op.getAdapterStepCode()));
    }

    private void persist(List<Candidate> group, LocalDateTime now) {
        var first = group.getFirst();
        var queue = queues.selectByIdentity(first.identity().mobileDigest(), first.spuId());
        if (queue == null) {
            queue =
                    RechargeFrequencyQueueDO.builder()
                            .mobileDigest(first.identity().mobileDigest())
                            .mobileMasked(first.identity().mobileMasked())
                            .platformSpuId(first.spuId())
                            .nextEnqueueSequence(1L)
                            .occupancyGeneration(0L)
                            .wakeGeneration(0L)
                            .version(0L)
                            .occupiedAlertActive(false)
                            .backlogAlertActive(false)
                            .createdAt(now)
                            .updatedAt(now)
                            .build();
            queues.insert(queue);
        }
        queue = queues.selectByIdForUpdate(queue.getId());
        var riskEntries = new ArrayList<RechargeFrequencyEntryDO>();
        if (group.stream().anyMatch(Candidate::risk)) {
            queue.setOccupancyGeneration(Math.incrementExact(queue.getOccupancyGeneration()));
        }
        for (var c : group) {
            var entry =
                    RechargeFrequencyEntryDO.builder()
                            .queueId(queue.getId())
                            .orderId(c.order().getId())
                            .orderNo(c.order().getOrderNo())
                            .fulfillmentRequestId(c.request().getId())
                            .enqueueSequence(queue.getNextEnqueueSequence())
                            .status(
                                    (c.risk()
                                                    ? RechargeFrequencyEntryStatus.HOLDING
                                                    : RechargeFrequencyEntryStatus.WAITING)
                                            .getCode())
                            .activeOperationNo(c.operation().getOperationNo())
                            .occupancyGeneration(c.risk() ? queue.getOccupancyGeneration() : 0L)
                            .dispatchPhase(
                                    c.risk()
                                            ? RechargeFrequencyDispatchPhase.SEND_POSSIBLE.getCode()
                                            : null)
                            .buyerId(c.order().getBuyerId())
                            .projectId(c.order().getProjectId())
                            .enqueuedAt(c.acceptedAt())
                            .migrationAcquiredAt(c.risk() ? c.acquiredAt() : null)
                            .migrationTimeBasis(
                                    c.risk()
                                            ? RechargeFrequencyMigrationTimeBasis.MIGRATION_CLOCK
                                                    .name()
                                            : null)
                            .createdAt(now)
                            .updatedAt(now)
                            .version(0L)
                            .build();
            entries.insert(entry);
            queue.setNextEnqueueSequence(Math.incrementExact(queue.getNextEnqueueSequence()));
            if (c.risk()) {
                riskEntries.add(entry);
            }
        }
        if (!riskEntries.isEmpty()) {
            queue.setHolderEntryId(riskEntries.size() == 1 ? riskEntries.getFirst().getId() : null);
            queue.setBlockedReason(riskEntries.size() > 1 ? MIGRATION_CONFLICT.name() : null);
            queue.setAcquiredAt(
                    riskEntries.stream()
                            .map(RechargeFrequencyEntryDO::getMigrationAcquiredAt)
                            .min(Comparator.naturalOrder())
                            .orElseThrow());
            queue.setMinimumHoldUntil(
                    riskEntries.stream()
                            .map(
                                    e ->
                                            e.getMigrationAcquiredAt()
                                                    .plusSeconds(
                                                            RechargeFrequencyConstants
                                                                    .SUCCESS_MINIMUM_HOLD_SECONDS))
                            .max(Comparator.naturalOrder())
                            .orElseThrow());
        }
        queue.setWakeGeneration(Math.incrementExact(queue.getWakeGeneration()));
        queue.setNextCheckAt(now);
        save(queue);
        scheduler.schedule(queue);
    }

    /**
     * 离线可信核实只委派既有结果与资金生命周期；拒绝permit，原迁移项/原操作/原代次谓词由Lifecycle锁后验证。
     * UNKNOWN/ACCEPTED/MANUAL等非权威终态没有结束风险的权限。 设计见
     * docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#101-迁移执行与核实边界。
     */
    @Transactional(
            transactionManager = "supplyChainTransactionManager",
            rollbackFor = Exception.class)
    public SupplierOrderLifecycle.TransitionResult confirmTrustedLegacyOutcome(
            String operationNo, OperationOutcome outcome, String traceId) {
        try {
            return confirmTrustedLegacyOutcomeInternal(operationNo, outcome, traceId);
        } catch (MigrationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(null, STORAGE_FAILURE, exception);
        }
    }

    private SupplierOrderLifecycle.TransitionResult confirmTrustedLegacyOutcomeInternal(
            String operationNo, OperationOutcome outcome, String traceId) {
        assertDeploymentEvidence();
        var locked = lifecycle.lockForResult(operationNo);
        if (!RechargeFrequencyLifecycle.isMigrationRisk(locked)
                || outcome == null
                || (outcome.outcomeClass() != OperationOutcomeClass.SUCCEEDED
                        && outcome.outcomeClass() != OperationOutcomeClass.REJECTED)) {
            throw failure(locked == null ? null : locked.order().getOrderNo(), UNRESOLVED_RISK);
        }
        return supplierLifecycle.confirmTrustedOutcome(operationNo, outcome, traceId, null);
    }

    /**
     * 全部风险可信结束后仍保留阻塞；仅离线CAS明确恢复，成功窗口按冻结时间取最大值，全失败无需追加窗口。 设计见
     * docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#101-迁移执行与核实边界。
     */
    @Transactional(
            transactionManager = "supplyChainTransactionManager",
            isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class)
    public void recoverBlocked(long queueId, long expectedGeneration, long expectedVersion) {
        try {
            recoverBlockedInternal(queueId, expectedGeneration, expectedVersion);
        } catch (MigrationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(null, STORAGE_FAILURE, exception);
        }
    }

    private void recoverBlockedInternal(
            long queueId, long expectedGeneration, long expectedVersion) {
        assertDeploymentEvidence();
        log.info(
                "[直充限频迁移] 离线解封入口 queueId={}, generation={}, version={}",
                queueId,
                expectedGeneration,
                expectedVersion);
        var queue = queues.selectByIdForUpdate(queueId);
        if (queue == null
                || !MIGRATION_CONFLICT.name().equals(queue.getBlockedReason())
                || queue.getHolderEntryId() != null
                || queue.getOccupancyGeneration() != expectedGeneration
                || queue.getVersion() != expectedVersion) {
            throw failure(null, STALE_QUEUE);
        }
        var locators =
                entries.selectList(
                        new LambdaQueryWrapper<RechargeFrequencyEntryDO>()
                                .eq(RechargeFrequencyEntryDO::getQueueId, queueId)
                                .isNotNull(RechargeFrequencyEntryDO::getMigrationAcquiredAt)
                                .orderByAsc(RechargeFrequencyEntryDO::getId));
        if (locators.size() < 2) {
            throw failure(null, UNRESOLVED_RISK);
        }
        for (var locator : locators) {
            entries.selectByIdForUpdate(locator.getId());
        }
        LocalDateTime successUntil = null;
        for (var locator : locators) {
            var locked = lifecycle.lockForResult(locator.getActiveOperationNo());
            if (locked == null
                    || !Objects.equals(locked.entry().getId(), locator.getId())
                    || locked.entry().getStatus() != RechargeFrequencyEntryStatus.FINISHED.getCode()
                    || locked.entry().getOccupancyGeneration() != expectedGeneration
                    || !trustedTerminal(locked.operation())
                    || (locked.request().getStatus() != FulfillmentRequestStatus.SUCCEEDED.getCode()
                            && locked.request().getStatus()
                                    != FulfillmentRequestStatus.FAILED.getCode())
                    || (locked.operation().getStatus()
                                    == SupplierOrderOperationStatus.SUCCEEDED.getCode())
                            != (locked.request().getStatus()
                                    == FulfillmentRequestStatus.SUCCEEDED.getCode())) {
                throw failure(locator.getOrderNo(), UNRESOLVED_RISK);
            }
            if (locked.operation().getStatus()
                    == SupplierOrderOperationStatus.SUCCEEDED.getCode()) {
                var until =
                        locator.getMigrationAcquiredAt()
                                .plusSeconds(
                                        RechargeFrequencyConstants.SUCCESS_MINIMUM_HOLD_SECONDS);
                if (successUntil == null || until.isAfter(successUntil)) {
                    successUntil = until;
                }
            }
        }
        if (successUntil != null && clock.now().isBefore(successUntil)) {
            throw failure(null, SUCCESS_WINDOW_PENDING);
        }
        assertDeploymentEvidence();
        queue.setBlockedReason(null);
        queue.setAcquiredAt(null);
        queue.setMinimumHoldUntil(null);
        queue.setNextCheckAt(clock.now());
        queue.setWakeGeneration(Math.incrementExact(queue.getWakeGeneration()));
        save(queue);
        scheduler.schedule(queue);
    }

    private void save(RechargeFrequencyQueueDO queue) {
        queue.setUpdatedAt(clock.now());
        if (queues.updateById(queue) != 1) {
            throw failure(null, STALE_QUEUE);
        }
    }

    private void assertDeploymentEvidence() {
        var evidence = deploymentEvidence.getIfAvailable();
        if (evidence == null) {
            throw failure(null, DEPLOYMENT_EVIDENCE_MISSING);
        }
        String version = environment.getProperty(TARGET_VERSION_PROPERTY);
        if (version == null || !version.matches("(?:[0-9a-f]{40}|sha256:[0-9a-f]{64})")) {
            throw failure(null, TARGET_VERSION_INVALID);
        }
        byte[] key = keyProvider.identityKey();
        if (key == null || key.length != 32) {
            throw failure(null, IDENTITY_KEY_INVALID);
        }
        try {
            evidence.assertQuiescent(version, MessageDigest.getInstance("SHA-256").digest(key));
        } catch (Exception exception) {
            throw failure(null, DEPLOYMENT_EVIDENCE_FAILED, exception);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /**
     * 仅受控离线附加配置实现：读取真实检查报告、确认全调用方停写、所有新旧SEND退出、Timer停止及节点版本/密钥一致。
     * 无默认实现，不把布尔声明或测试实现当生产验收，不自动恢复受理/SEND；发布流程须另行验收迁移完整性。
     */
    @FunctionalInterface
    public interface DeploymentQuiescenceEvidencePort {
        void assertQuiescent(String immutableTargetVersion, byte[] identityKeyFingerprint);
    }

    public record MigrationReport(int migratedCount, int reusedCount) {}

    private record Candidate(
            SupplyChainOrderDO order,
            FulfillmentRequestDO request,
            FulfillmentOperationDO operation,
            RechargeFrequencyIdentityService.MobileIdentity identity,
            long spuId,
            LocalDateTime acceptedAt,
            boolean risk,
            LocalDateTime acquiredAt) {}

    private static MigrationException failure(
            String orderNo, RechargeFrequencyMigrationCodes code) {
        return new MigrationException(orderNo, code, null);
    }

    private static MigrationException failure(
            String orderNo, RechargeFrequencyMigrationCodes code, Throwable cause) {
        return new MigrationException(orderNo, code, cause);
    }

    /** 安全错误保留单号/类别及异常类型、位置，绝不拼接解密/SQL原始报文。 */
    public static final class MigrationException extends IllegalStateException {
        private final RechargeFrequencyMigrationCodes code;

        MigrationException(
                String orderNo, RechargeFrequencyMigrationCodes code, Throwable original) {
            super(
                    "直充限频迁移拒绝 code=" + code + ", orderNo=" + safeOrderNo(orderNo),
                    safeCause(original));
            this.code = code;
        }

        public RechargeFrequencyMigrationCodes code() {
            return code;
        }
    }

    private static String safeOrderNo(String orderNo) {
        return orderNo == null
                ? "unavailable"
                : orderNo.replaceAll("(?<![0-9])1[3-9][0-9]{9}(?![0-9])", "[masked]");
    }

    private static Throwable safeCause(Throwable original) {
        if (original == null) {
            return null;
        }
        var safe = new IllegalStateException(original.getClass().getName());
        safe.setStackTrace(
                original.getStackTrace().length == 0
                        ? new StackTraceElement[0]
                        : new StackTraceElement[] {original.getStackTrace()[0]});
        if (original.getCause() != null && original.getCause() != original) {
            safe.initCause(safeCause(original.getCause()));
        }
        return safe;
    }
}
