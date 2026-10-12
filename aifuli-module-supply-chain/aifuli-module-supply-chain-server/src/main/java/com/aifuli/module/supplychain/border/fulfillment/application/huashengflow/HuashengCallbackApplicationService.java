package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.BOrderDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.BOrderMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationAssetFact;
import com.aifuli.module.supplychain.infrastructure.security.FrozenCredentialSetResolver;
import com.aifuli.module.supplychain.supplieradapter.huasheng.HuashengCrypto;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.*;
import com.aifuli.module.supplychain.supplieradapter.metadata.HuashengOrderV1Metadata;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorVersionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.InputStream;
import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 华昇回调应用服务。
 *
 * <p>职责：
 * <ul>
 *   <li>定位冻结连接器版本，校验 merchantAccount 与 Header Appid；</li>
 *   <li>解析回调公钥与密钥，委托严格协议解析器验签解密；</li>
 *   <li>严格按锁序执行：进程互斥锁（3秒超时）-&gt; Inbox 防重 -&gt; 领域聚合锁 -&gt; 资金锁；</li>
 *   <li>在同一供应链事务内完成 Inbox 写入、原单定位、生成推进、资产写入与资金流转。</li>
 * </ul>
 *
 * <p>设计见 docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#8-必需华昇回调。
 */
@Service
@Slf4j
public class HuashengCallbackApplicationService {

    private final ReentrantLock huashengExecutionLock;
    private final HuashengCallbackInbox callbackInbox;
    private final HuashengFulfillmentLifecycle lifecycle;
    private final BOrderMapper bOrderMapper;
    private final PlatformTransactionManager transactionManager;
    private final SupplierConnectorVersionMapper versionMapper;
    private final SupplierConnectorMapper connectorMapper;
    private final ObjectProvider<PublishedConnectorConfigurationResolver> connectorResolverProvider;
    private final ObjectProvider<FrozenCredentialSetResolver> credentialResolverProvider;
    private final HuashengType201AssetCodec assetCodec = new HuashengType201AssetCodec();

    public HuashengCallbackApplicationService(
            @Qualifier("huashengExecutionLock") ObjectProvider<ReentrantLock> lockProvider,
            HuashengCallbackInbox callbackInbox,
            HuashengFulfillmentLifecycle lifecycle,
            BOrderMapper bOrderMapper,
            @Qualifier("supplyChainTransactionManager") ObjectProvider<PlatformTransactionManager> txManagerProvider,
            SupplierConnectorVersionMapper versionMapper,
            SupplierConnectorMapper connectorMapper,
            ObjectProvider<PublishedConnectorConfigurationResolver> connectorResolverProvider,
            ObjectProvider<FrozenCredentialSetResolver> credentialResolverProvider) {
        this.huashengExecutionLock = lockProvider.getIfAvailable(ReentrantLock::new);
        this.callbackInbox = Objects.requireNonNull(callbackInbox, "callbackInbox must not be null");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        this.bOrderMapper = Objects.requireNonNull(bOrderMapper, "bOrderMapper must not be null");
        this.transactionManager = txManagerProvider.getIfAvailable();
        this.versionMapper = Objects.requireNonNull(versionMapper, "versionMapper must not be null");
        this.connectorMapper = Objects.requireNonNull(connectorMapper, "connectorMapper must not be null");
        this.connectorResolverProvider = Objects.requireNonNull(connectorResolverProvider, "connectorResolverProvider must not be null");
        this.credentialResolverProvider = Objects.requireNonNull(credentialResolverProvider, "credentialResolverProvider must not be null");
    }

    public void handleCallback(Long connectorVersionId, String appIdHeader, String xSignHeader, InputStream inputStream, String traceId) {
        if (inputStream == null) {
            throw new HuashengCallbackValidationException("Callback input stream is null");
        }
        byte[] bodyBytes = HuashengCallbackParser.readBounded(inputStream, HuashengCallbackParser.MAX_BODY_BYTES);
        handleCallback(connectorVersionId, appIdHeader, xSignHeader, bodyBytes, traceId);
    }

    public void handleCallback(Long connectorVersionId, String appIdHeader, String xSignHeader, byte[] rawBodyBytes, String traceId) {
        if (connectorVersionId == null) {
            throw new HuashengCallbackNotFoundException("Connector version id is null");
        }
        SupplierConnectorVersionDO version = versionMapper.selectById(connectorVersionId);
        if (version == null) {
            throw new HuashengCallbackNotFoundException("Connector version not found: " + connectorVersionId);
        }
        SupplierConnectorDO connector = connectorMapper.selectById(version.getConnectorId());
        if (connector == null || !"HUA_SHENG_ORDER_V1".equals(connector.getAdapterType())) {
            throw new HuashengCallbackValidationException("Invalid connector or adapter type: " + connectorVersionId);
        }
        if (appIdHeader == null || !appIdHeader.equals(version.getMerchantAccount())) {
            throw new HuashengCallbackValidationException("Header Appid mismatch with merchant account");
        }

        PublishedConnectorConfigurationResolver connectorResolver = connectorResolverProvider.getIfAvailable();
        FrozenCredentialSetResolver credentialResolver = credentialResolverProvider.getIfAvailable();
        if (connectorResolver == null || credentialResolver == null) {
            throw new IllegalStateException("Required credential or connector resolver is not configured");
        }

        var config = connectorResolver.resolve(connectorVersionId);
        Map<String, char[]> resolved = credentialResolver.resolve(
                config.credentialSetId(),
                config.frozenCredentialItems(),
                Set.of(HuashengOrderV1Metadata.APP_SECRET, HuashengOrderV1Metadata.CALLBACK_PUBLIC_KEY)
        );

        char[] appSecretChars = resolved.get(HuashengOrderV1Metadata.APP_SECRET);
        char[] callbackPublicKeyChars = resolved.get(HuashengOrderV1Metadata.CALLBACK_PUBLIC_KEY);
        HuashengCallbackFact fact;
        try {
            if (appSecretChars == null || callbackPublicKeyChars == null) {
                throw new HuashengCallbackValidationException("Missing required callback credential keys");
            }
            PublicKey callbackPublicKey;
            try { callbackPublicKey = HuashengCrypto.loadPublicKey(new String(callbackPublicKeyChars)); }
            catch (Exception invalidKey) { throw new HuashengCallbackValidationException("Invalid callback public key"); }
            fact = HuashengCallbackParser.parse(
                    rawBodyBytes,
                    appIdHeader,
                    xSignHeader,
                    config.merchantAccount(),
                    callbackPublicKey,
                    new String(appSecretChars)
            );
        } catch (HuashengCallbackParseException e) {
            throw new HuashengCallbackValidationException("Invalid callback fact");
        } finally {
            resolved.values().forEach(chars -> {
                if (chars != null) Arrays.fill(chars, '\0');
            });
        }

        handleCallbackFact(connector.getSupplierId(), connectorVersionId, fact, traceId);
    }

    public void handleCallbackFact(Long supplierId, Long connectorVersionId, HuashengCallbackFact fact, String traceId) {
        Objects.requireNonNull(supplierId, "supplierId must not be null");
        Objects.requireNonNull(connectorVersionId, "connectorVersionId must not be null");
        Objects.requireNonNull(fact, "fact must not be null");

        // 1. 严格锁序：进程互斥锁（3秒超时）
        boolean locked;
        try {
            locked = huashengExecutionLock.tryLock(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HuashengLockBusyException("Interrupted while waiting for Huasheng execution lock", e);
        }
        if (!locked) {
            throw new HuashengLockBusyException("Timeout waiting for Huasheng execution lock for connectorVersionId: " + connectorVersionId);
        }

        java.util.concurrent.atomic.AtomicReference<Long> bOrderIdRef = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<HuashengStepFact> stepFactRef = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            // 2. 事务内执行
            TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
            txTemplate.executeWithoutResult(status -> {
                // a. Inbox 防重认领
                HuashengCallbackInbox.InboxClaim claim = callbackInbox.claim(supplierId, connectorVersionId, fact, traceId);
                if (claim.status() == HuashengCallbackInbox.InboxStatus.ALREADY_PROCESSED) {
                    log.info("[华昇回调][幂等直接返回] nonce={} 已处理成功", fact.nonce());
                    return;
                }

                // b. 定位原 BOrder
                BOrderDO bOrder = bOrderMapper.selectOne(new LambdaQueryWrapper<BOrderDO>()
                        .eq(BOrderDO::getSupplierId, supplierId)
                        .eq(BOrderDO::getConnectorVersionId, connectorVersionId)
                        .eq(BOrderDO::getBillno, fact.outOrderSn()));
                if (bOrder == null) {
                    log.warn("[华昇回调][未找到原单] supplierId={}, connectorVersionId={}, outOrderSn={}",
                            supplierId, connectorVersionId, fact.outOrderSn());
                    throw new HuashengCallbackNotFoundException(
                            "BOrder not found for outOrderSn: " + fact.outOrderSn());
                }
                bOrderIdRef.set(bOrder.getId());

                // c. 构造 HuashengStepFact
                // 保留 codec 生成的完整事实；不能丢标准字段后再用 builder 伪造资产（设计 §7.1）。
                List<OperationAssetFact> assets = fact.card().map(c -> List.of(assetCodec.toFact(c))).orElse(List.of());
                byte[] protectedPayload = assets.isEmpty() ? null : assets.getFirst().protectedPayload();
                HuashengStepFact stepFact = new HuashengStepFact(
                        HuashengStepFact.Endpoint.CALLBACK,
                        HuashengStepFact.StepResult.ACCEPTED,
                        fact.outOrderSn(),
                        fact.orderSn(),
                        // 正式 FAILED 表示零交付；原 num 省略仍保留在协议事实/Inbox 摘要中。
                        // 原单订购数量由锁后 checkpoint 核实，不用回调零交付改写订购数量。
                        // 设计：docs/superpowers/specs/2026-10-12-huasheng-fulfillment-hardening-design.md#21-订购数量与交付数量。
                        fact.num() == null ? HuashengFlowCodes.FAILED_CALLBACK_DELIVERY_QUANTITY : fact.num(),
                        fact.rawTotalPrice(),
                        fact.totalPrice().minorUnits(),
                        fact.status() == HuashengOrderStatus.SUCCESS ? HuashengStepFact.Status.SUCCESS : HuashengStepFact.Status.FAILED,
                        HuashengStepFact.RefundStatus.ABSENT,
                        HuashengStepFact.IsTake.ABSENT,
                        protectedPayload,
                        List.of()
                );
                stepFactRef.set(stepFact);

                // d. 推进生命周期
                HuashengApplyResult applyResult = lifecycle.applyCallback(bOrder.getId(), stepFact, assets, traceId);
                if (applyResult.decision() == null) {
                    log.warn("[华昇回调][回调失格] bOrderId={}, message={}", bOrder.getId(), applyResult.message());
                    throw new HuashengCallbackNotFoundException(
                            "Callback disqualified for bOrderId: " + bOrder.getId() + ", message: " + applyResult.message());
                }

                // e. 标记成功
                callbackInbox.markSucceeded(claim.inboxEventId(), claim.version());
                // 200 表示事务处理完成而非必然业务成功；外层事务回滚时不得记录完成（设计 §8.2）。
                String nonce = fact.nonce();
                Long orderId = bOrder.getId();
                HuashengFlowDecision decision = applyResult.decision();
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() {
                        log.info("[华昇回调][处理完成] nonce={}, bOrderId={}, decision={}", nonce, orderId, decision);
                    }
                });
            });
        } catch (Exception e) {
            if (isDuplicateKeyViolation(e)) {
                log.error("[华昇回调][跨单资产唯一键冲突] supplierId={}, outOrderSn={}, nonce={}",
                        supplierId, fact.outOrderSn(), fact.nonce(), e);
                Long bOrderId = bOrderIdRef.get();
                HuashengStepFact stepFact = stepFactRef.get();
                if (bOrderId != null && stepFact != null) {
                    try {
                        lifecycle.recordFactConflictAndHoldManual(bOrderId, stepFact, traceId);
                    } catch (Exception recEx) {
                        log.error("[华昇回调][独立事务留证失败] bOrderId={}", bOrderId, recEx);
                    }
                }
                throw new HuashengCallbackConflictException("Duplicate asset conflict across orders: " + e.getMessage(), e);
            }
            throw e;
        } finally {
            huashengExecutionLock.unlock();
        }
    }

    private static boolean isDuplicateKeyViolation(Throwable throwable) {
        Throwable curr = throwable;
        while (curr != null) {
            if (curr instanceof org.springframework.dao.DuplicateKeyException) return true;
            if (curr instanceof java.sql.SQLIntegrityConstraintViolationException) return true;
            curr = curr.getCause();
        }
        return false;
    }
}
