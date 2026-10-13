package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.BOrderDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentAcceptanceReceiptDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentOperationDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentRequestDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.SupplierRoutingRecordDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.SupplierRoutingRoundDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.BOrderMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentAcceptanceReceiptMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentOperationMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentRequestMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.SupplierRoutingRecordMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.SupplierRoutingRoundMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceReceiptStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceRejectionCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.AdapterStepCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentIdempotencyKeys;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentInitialStatuses;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOperationKind;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOutboxCodes;
import com.aifuli.module.supplychain.border.fulfillment.domain.StandardAcceptanceFingerprintPolicy;
import com.aifuli.module.supplychain.border.order.dal.dataobject.OrderStatusLogDO;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.OrderStatusLogMapper;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.OrderStatusAuditCodes;
import com.aifuli.module.supplychain.border.order.domain.OrderSummaryDecision;
import com.aifuli.module.supplychain.border.order.domain.OrderSummaryFact;
import com.aifuli.module.supplychain.border.order.domain.OrderSummaryPolicyRegistry;
import com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderTypes;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.routing.application.SupplierRoutingApplicationService;
import com.aifuli.module.supplychain.tradecore.catalog.application.CurrentSupplyEligibilityReader;
import com.aifuli.module.supplychain.tradecore.catalog.application.SupplyQualification;
import com.aifuli.module.supplychain.routing.domain.RoutingCandidate;
import com.aifuli.module.supplychain.routing.domain.RoutingDecisionStatus;
import com.aifuli.module.supplychain.routing.domain.RoutingInput;
import com.aifuli.module.supplychain.routing.domain.RoutingRecordDecisionCode;
import com.aifuli.module.supplychain.routing.domain.RoutingSubjectType;
import com.aifuli.module.supplychain.routing.domain.RoutingType;
import com.aifuli.module.supplychain.tradecore.account.application.FundReservationService;
import com.aifuli.module.supplychain.tradecore.account.domain.FundReferenceType;
import com.aifuli.module.supplychain.tradecore.account.domain.Money;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingAvailabilityDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingAvailabilityMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.ListingAvailability;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductSellabilityService;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerAcceptanceCatalogResolver;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerAcceptanceCatalogResolver.CatalogIdentity;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.ProjectQuoteKeyContext;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.ProductNotSaleableException;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.StandardPricingQuoteService;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.StandardPricingQuoteService.FrozenQuote;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver.PublishedConnectorConfiguration;
import com.aifuli.module.supplychain.supplieradapter.metadata.HuashengOrderV1Metadata;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengCheckpoint;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengCheckpointCodec;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengFlowCodes;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengFlowPolicy;
import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aifuli.framework.common.exception.ServiceException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 标准商品受理回执、总订单、路由、BOrder 和资金冻结的原子编排。
 *
 * <p>稳定编码目录见
 * {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#721-标准履约稳定编码与-java-领域枚举}；
 * PROCESSING 回执恢复语义见
 * {@code docs/superpowers/plans/2026-08-11-supply-chain-m1-qnm-vertical-slice.md#task-9}；
 * 一期不因可用余额不足拒单，缺账户拒绝见
 * {@code docs/superpowers/specs/2026-08-17-supply-chain-phase1-buyer-account-overdraft.md}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FulfillmentAcceptanceLifecycle {

    private final SupplyChainDatabaseClock databaseClock;

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";

    /**
     * 受理请求指纹格式版本2：无用户规范化格式（历史含用户身份的格式0回执不可用于重放比较）。
     * 与 052 变更集 fingerprint_version 列 CHECK 语义一致。
     */
    private static final short FINGERPRINT_FORMAT_NO_USER = 2;

    /**
     * 启用「每人每周期限购」但本单无可用手机号（卡券/非手机号账号）时写入订单的内部备注，
     * 不向项目接口返回。文案见设计 §4。
     */
    private static final String PURCHASE_LIMIT_SKIP_REMARK = "该商品无可用手机号，本单未执行每人每周期限购";

    private final FulfillmentAcceptanceReceiptMapper receiptMapper;
    private final SupplyChainOrderMapper orderMapper;
    private final OrderStatusLogMapper statusLogMapper;
    private final FulfillmentRequestMapper requestMapper;
    private final SupplierRoutingRoundMapper routingRoundMapper;
    private final SupplierRoutingRecordMapper routingRecordMapper;
    private final BOrderMapper bOrderMapper;
    private final FulfillmentOperationMapper operationMapper;
    private final BuyerListingMapper listingMapper;
    private final BuyerAcceptanceCatalogResolver catalogResolver;
    private final BuyerListingVersionMapper listingVersionMapper;
    private final BuyerListingAvailabilityMapper listingAvailabilityMapper;
    private final PlatformSkuMapper skuMapper;
    private final ProductSellabilityService productSellabilityService;
    private final SkuPurchaseLimitService purchaseLimitService;
    private final StandardPricingQuoteService pricingQuoteService;
    private final SupplierRoutingApplicationService routingService;
    private final CurrentSupplyEligibilityReader eligibilityReader;
    private final FundReservationService fundReservationService;
    private final RechargeFrequencyLifecycle rechargeFrequencyLifecycle;
    private final OutboxEventRepository outboxRepository;
    private final BusinessNoGenerator businessNoGenerator;
    private final ObjectMapper objectMapper;
    private final FulfillmentTargetSchemaRegistry targetSchemaRegistry;
    private final OrderSummaryPolicyRegistry orderSummaryPolicyRegistry;
    private final ObjectProvider<AcceptanceFailureInjector> failureInjector;
    private final ObjectProvider<PublishedConnectorConfigurationResolver> connectorResolverProvider;
    private final ObjectProvider<SupplierOfferVersionMapper> offerVersionMapperProvider;
    private final ObjectProvider<HuashengCheckpointCodec> checkpointCodecProvider;
    private final ObjectProvider<PersistentTimer> persistentTimerProvider;
    private final ObjectProvider<FulfillmentCallbackProperties> callbackPropertiesProvider;

    /**
     * 在敏感目标加密前，根据采购商商品关系解析平台 SKU 持有的权威 Schema。
     *
     * <p>这里只读取 SKU 的稳定协议元数据，不以当前可售状态改变请求指纹；Listing 版本、商品可售、库存和路由等
     * 业务条件仍在受理事务内校验。
     * 无法解析时返回不可用快照，让 Lifecycle 按原有业务拒绝顺序收敛，避免把预读取失败变成 HTTP 异常。
     * 设计见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#72-fulfillmentrequest路由与-supplierorder}。</p>
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, readOnly = true)
    public TargetSchemaSnapshot resolveTargetSchema(Long buyerId, String buyerSkuCode) {
        log.debug("[履约][lifecycle] resolveTargetSchema 入口 buyerId={}, buyerSkuCode={}", buyerId, buyerSkuCode);
        CatalogIdentity identity = catalogResolver.resolve(buyerId, buyerSkuCode);
        if (!identity.available()) {
            return TargetSchemaSnapshot.unavailable();
        }
        PlatformSkuDO sku = skuMapper.selectById(identity.platformSkuId());
        if (sku == null || sku.getTargetSchemaCode() == null || sku.getTargetSchemaVersion() == null) {
            return TargetSchemaSnapshot.unavailable();
        }
        // platformSkuId 是服务端解析的商品身份，供格式 2 指纹的「服务端冻结商品身份（可解析时）」成分。
        return new TargetSchemaSnapshot(identity.platformSkuId(),
                sku.getTargetSchemaCode(), sku.getTargetSchemaVersion(), true, identity);
    }

    /**
     * v2首次真实SKU/Schema解析不要求LST已发布；缺SKU/BPD在claim前失败，Schema损坏不填占位值。
     * 设计见 docs/superpowers/specs/2026-10-12-platform-sku-order-contract-design.md#41-冻结优先。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, readOnly = true)
    public TargetSchemaSnapshot resolvePlatformSkuTargetSchema(Long buyerId, String platformSkuCode) {
        var resolved = catalogResolver.resolvePlatformSku(buyerId, platformSkuCode);
        if (resolved == null) {
            throw com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception(
                    com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SKU_NOT_SALEABLE);
        }
        try {
            if (resolved.schemaVersion() == null) { throw new FulfillmentTargetSchemaRegistry.InvalidTargetException(); }
            targetSchemaRegistry.requireSupportedSchema(resolved.schemaCode(), resolved.schemaVersion());
        } catch (FulfillmentTargetSchemaRegistry.InvalidTargetException invalid) {
            throw new IllegalStateException("平台SKU目标Schema引用损坏", invalid);
        }
        return new TargetSchemaSnapshot(resolved.catalogIdentity().platformSkuId(), resolved.schemaCode(),
                resolved.schemaVersion(), true, resolved.catalogIdentity());
    }

    /**
     * 抢占受理回执：按项目永久键 (projectId, buyerOrderNo) 幂等创建 PROCESSING 回执或回放已有终态。
     *
     * <p>首次创建不加行锁，靠项目键唯一索引裁决并发；并发首创落败方按项目键当前读重探测获胜回执并
     * 比较指纹——同参数沿用既有回执语义，异参幂等冲突，不捕获任意键冲突后盲回放。格式 2 无用户指纹
     * 一致且仍 PROCESSING 时允许本次重试继续 accept。可重试拒绝携带当前 positive
     * previousReceiptVersion 才 CAS 重开，不要求调用方更换 Listing 版本；回执 listing_version_no
     * 由服务端解析当前发布版本写入。详见
     * {@code docs/superpowers/specs/2026-10-02-standard-acceptance-project-key-design.md#3-回执竞争、重试和真实返回}、
     * {@code docs/superpowers/plans/2026-08-11-supply-chain-m1-qnm-vertical-slice.md#task-9}。</p>
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public ClaimResult claim(PreparedAcceptance command) {
        log.info("[履约][lifecycle] claim 入口 projectId={}, buyerId={}, buyerOrderNo={}, buyerSkuCode={}, quantity={}, traceId={}",
                command.projectId(), command.buyerId(), command.buyerOrderNo(), command.buyerSkuCode(),
                command.quantity(), command.traceId());
        // 首次探测不加锁：REPEATABLE READ 下对不存在唯一键 FOR UPDATE
        // 会让并发首请求同时持有间隙锁，再插入时形成死锁。唯一索引才是创建竞争裁决者。
        FulfillmentAcceptanceReceiptDO existing = receiptMapper.selectByProjectKey(
                command.projectId(), command.buyerOrderNo());
        if (existing != null && !Objects.equals(existing.getFingerprintVersion(), command.fingerprintVersion())) {
            throw new IdempotencyConflictException();
        }
        BuyerListingDO gateListing = null;
        if (existing == null || existing.getStatus() != AcceptanceReceiptStatus.ACCEPTED.getCode()
                || !Arrays.equals(existing.getRequestFingerprint(), command.requestFingerprint())) {
            // 已接受同指纹请求只回放；其余MOBILE写路径先核验门禁，暂时关闭不固化为业务终拒。
            // 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#101-迁移执行与核实边界。
            CatalogIdentity identity = command.catalogIdentity() == null && !command.platformSkuRequest()
                    ? catalogResolver.resolve(command.buyerId(), command.buyerSkuCode()) : command.catalogIdentity();
            gateListing = identity != null && identity.available() ? listingMapper.selectById(identity.listingId()) : null;
            if (gateListing != null) {
                rechargeFrequencyLifecycle.requireAcceptanceReady(gateListing.getPlatformSkuId(), command.targetAccountType());
            } else if (existing == null && command.platformSkuRequest()) {
                rechargeFrequencyLifecycle.requireAcceptanceReady(command.frozenPlatformSkuId(), command.targetAccountType());
            }
        }
        if (existing == null) {
            LocalDateTime now = now();
            FulfillmentAcceptanceReceiptDO receipt = FulfillmentAcceptanceReceiptDO.builder()
                    .receiptNo(businessNoGenerator.next(BusinessNoPrefixes.RCP))
                    .buyerId(command.buyerId()).projectId(command.projectId()).buyerOrderNo(command.buyerOrderNo())
                    .buyerSkuCode(command.buyerSkuCode())
                    .requestedPlatformSkuCode(command.requestedPlatformSkuCode())
                    // 受理时冻结的平台SKU/目标Schema版本：仅服务端可解析的真实值，无解析值时不补假数据。
                    .platformSkuId(command.platformSkuRequest() ? command.frozenPlatformSkuId()
                            : gateListing == null ? null : gateListing.getPlatformSkuId())
                    .quantity(command.quantity()).targetSchemaCode(command.targetSchemaCode())
                    .targetSchemaVersion(command.targetSchemaVersion())
                    .targetDigest(command.targetDigest()).invariantFingerprint(command.invariantFingerprint())
                    .requestFingerprint(command.requestFingerprint())
                    .fingerprintVersion(command.fingerprintVersion())
                    .listingVersionNo(resolveAttemptListingVersionNo(command, gateListing)).attemptNo(1)
                    .status(AcceptanceReceiptStatus.PROCESSING.getCode())
                    .createdAt(now).updatedAt(now).version(0L).build();
            try {
                receiptMapper.insert(receipt);
            } catch (DuplicateKeyException duplicate) {
                // 项目键唯一索引裁决并发首创竞争。此处必须结束本事务后由 Facade 以新事务重探测：
                // insert 失败的语句在多个唯一索引（含 result_order 唯一索引的 supremum gap）残留的
                // 意向/gap 锁若随本事务继续持有，会与获胜方 accept 收敛（markAccepted 更新
                // result_order 唯一索引）形成死锁环。专用异常仅承载项目键唯一冲突，Facade 不得
                // 捕获任意键冲突盲回放。
                // 设计见 docs/superpowers/specs/2026-10-02-standard-acceptance-project-key-design.md#3-回执竞争、重试和真实返回。
                throw new ProjectKeyClaimRaceException(duplicate);
            }
            log.info("[履约][lifecycle] claim 新建回执 receiptId={}, receiptNo={}", receipt.getId(), receipt.getReceiptNo());
            return new ClaimResult(receipt.getId(), true, false);
        }
        existing = receiptMapper.selectByProjectKeyForUpdate(command.projectId(), command.buyerOrderNo());
        if (existing == null) {
            throw new IllegalStateException("Acceptance receipt disappeared during row-lock acquisition");
        }
        return claimExisting(existing, command);
    }

    /**
     * 并发首创落败方的新事务重探测（由 Facade 在 claim 以 {@link ProjectKeyClaimRaceException}
     * 结束旧事务后调用）：项目键当前读锁定获胜回执并按既有语义收敛——同参数回放/续跑/CAS 重开，
     * 异参幂等冲突。重探测无行说明冲突不来自项目键（其他唯一键/系统故障），保留原异常传播，不盲回放。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public ClaimResult claimAfterProjectKeyRace(PreparedAcceptance command) {
        log.info("[履约][lifecycle] 并发首创冲突按项目键重探测 projectId={}, buyerOrderNo={}, traceId={}",
                command.projectId(), command.buyerOrderNo(), command.traceId());
        FulfillmentAcceptanceReceiptDO winner = receiptMapper.selectByProjectKeyForUpdate(
                command.projectId(), command.buyerOrderNo());
        if (winner == null) {
            throw new IllegalStateException("Project key race winner disappeared during re-probe");
        }
        return claimExisting(winner, command);
    }

    /** 已有回执的竞争收敛：指纹一致按状态回放/续跑/CAS 重开；身份不同一律幂等冲突（旧格式失败关闭）。 */
    private ClaimResult claimExisting(FulfillmentAcceptanceReceiptDO existing, PreparedAcceptance command) {
        if (Objects.equals(existing.getFingerprintVersion(), command.fingerprintVersion())
                && Arrays.equals(existing.getRequestFingerprint(), command.requestFingerprint())) {
            // 格式3续跑/CAS在改回执前按冻结SKU核限频门禁，不解析当前目录，也不让终态回放依赖门禁。
            if (command.platformSkuRequest() && (existing.getStatus() == AcceptanceReceiptStatus.PROCESSING.getCode()
                    || existing.getStatus() == AcceptanceReceiptStatus.REJECTED_RETRYABLE.getCode()
                    && command.previousReceiptVersion() != null
                    && Objects.equals(command.previousReceiptVersion(), existing.getVersion()))) {
                rechargeFrequencyLifecycle.requireAcceptanceReady(command.frozenPlatformSkuId(), command.targetAccountType());
            }
            // 同一请求重试：回执仍处于 PROCESSING 说明上一次 accept 未提交，
            // 由本次重试重新执行 accept（幂等，行锁串行化）；已终态则直接回放快照。
            if (existing.getStatus() == AcceptanceReceiptStatus.PROCESSING.getCode()) {
                return new ClaimResult(existing.getId(), true, false);
            }
            if (isReleasedQuotaRetry(existing, command)) {
                reopenRetryable(existing, command);
                return new ClaimResult(existing.getId(), true, false);
            }
            // 可重试拒绝携带当前 positive previousReceiptVersion 才 CAS 重开，不要求调用方更换 Listing 版本。
            // 设计见 docs/superpowers/specs/2026-10-02-standard-acceptance-project-key-design.md#3-回执竞争、重试和真实返回。
            if (existing.getStatus() == AcceptanceReceiptStatus.REJECTED_RETRYABLE.getCode()
                    && command.previousReceiptVersion() != null
                    && Objects.equals(command.previousReceiptVersion(), existing.getVersion())) {
                reopenRetryable(existing, command);
                return new ClaimResult(existing.getId(), true, false);
            }
            return new ClaimResult(existing.getId(), false, true);
        }
        // 格式 2 下 invariant 与 request 指纹同源：身份不同的请求一律幂等冲突，
        // 旧格式历史回执（含用户/版本 hash）同样失败关闭，不得按新格式比较或重新建单。
        throw new IdempotencyConflictException();
    }

    /**
     * 受理主路径：校验可售 Listing、路由供应商、建总订单/BOrder 并冻结资金。
     *
     * <p>本方法只写本地事务，不调用供应商适配器。一期冻结不检查可用余额；缺账户映射为
     * {@link AcceptanceRejectionCode#BUYER_ACCOUNT_NOT_OPENED}。详见
     * {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#7.2-标准履约受理}、
     * {@code docs/superpowers/specs/2026-08-17-supply-chain-phase1-buyer-account-overdraft.md}。</p>
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public AcceptanceSnapshot accept(long receiptId, PreparedAcceptance command) {
        return acceptPrepared(receiptId, command, false);
    }

    /**
     * HTTP直接下单最终事务入口，经真实代理开启受理事务；报价锁后当前准入锁一直持到提交。
     * 原RPC入口保留预签报价契约，两入口共用受理实现。
     * 设计见 docs/superpowers/specs/2026-10-03-standard-project-direct-order-price-freeze-design.md#3-定价身份与当前准入。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public AcceptanceSnapshot acceptDirectProjectOrder(long receiptId, PreparedAcceptance command) {
        return acceptPrepared(receiptId, command, true);
    }

    private AcceptanceSnapshot acceptPrepared(long receiptId, PreparedAcceptance command, boolean directProjectOrder) {
        log.info("[履约][lifecycle] accept 入口 receiptId={}, buyerOrderNo={}, traceId={}",
                receiptId, command.buyerOrderNo(), command.traceId());
        FulfillmentAcceptanceReceiptDO receipt = receiptMapper.selectByIdForUpdate(receiptId);
        if (receipt == null) {
            throw new IdempotencyConflictException();
        }
        // 并发重试场景：另一线程已在本事务之外完成 accept 并提交，直接回放终态快照。
        if (receipt.getStatus() == AcceptanceReceiptStatus.ACCEPTED.getCode()) {
            if (!Objects.equals(receipt.getFingerprintVersion(), command.fingerprintVersion())
                    || !Arrays.equals(receipt.getRequestFingerprint(), command.requestFingerprint())) {
                throw new IdempotencyConflictException();
            }
            return snapshot(receiptId, true);
        }
        requireProcessingReceipt(receipt, command);
        if (command.quantity() != 1) {
            throw rejection(receiptId, null, AcceptanceReceiptStatus.REJECTED_FINAL,
                    AcceptanceRejectionCode.QUANTITY_NOT_SUPPORTED);
        }
        if (!command.targetValid()) {
            throw rejection(receiptId, null, AcceptanceReceiptStatus.REJECTED_FINAL,
                    AcceptanceRejectionCode.INVALID_TARGET);
        }

        LocalDateTime now = now();
        LockedListing locked = lockListingAndSku(command);
        ListingSnapshot listing = requireSaleableListing(command, now, locked, directProjectOrder);
        // 报价消费按项目永久键锁定首次冻结报价，不携带用户、支付时间、报价 token 或调用方商品版本；
        // quoteId/listingId 等真实冻结值经 FrozenQuote 进入本事务持久化（回执级新列见 052 增量）。
        // 设计见 docs/superpowers/specs/2026-10-02-standard-internal-quote-freeze-design.md#3-受理内查取与唯一消费。
        FrozenQuote frozenQuote = pricingQuoteService.requireForProject(new ProjectQuoteKeyContext(
                command.buyerId(), command.projectId(), listing.sku().getId(),
                command.buyerOrderNo(), command.quantity()));
        if (directProjectOrder) {
            try {
                pricingQuoteService.requireCurrentProjectAdmission(new ProjectQuoteKeyContext(
                        command.buyerId(), command.projectId(), listing.sku().getId(),
                        command.buyerOrderNo(), command.quantity()), frozenQuote);
            } catch (ProductNotSaleableException unavailable) {
                throw new BusinessRejection(receiptId, listing.version().getId(),
                        AcceptanceReceiptStatus.REJECTED_RETRYABLE, AcceptanceRejectionCode.SKU_NOT_SALEABLE, unavailable);
            }
        }
        // 「每人」限购以实际履约目标为输入（已验证的targetAccountType+规范化MOBILE手机号），
        // 不再接收购买人身份；卡券/非手机号跳过该维度，由订单内部备注经独立判定记录一次。
        // 设计见 docs/superpowers/specs/2026-10-02-standard-acceptance-project-key-design.md#4-手机号限购与内部备注。
        SkuPurchaseLimitService.Violation limitViolation = purchaseLimitService.validateLockedSku(
                command.projectId(), command.targetAccountType(), command.fulfillmentAccountValue(),
                listing.sku(), command.quantity(), now);
        if (limitViolation == SkuPurchaseLimitService.Violation.ORDER_LIMIT_EXCEEDED) {
            throw rejection(receiptId, listing.version().getId(), AcceptanceReceiptStatus.REJECTED_FINAL,
                    AcceptanceRejectionCode.ORDER_PURCHASE_LIMIT_EXCEEDED);
        }
        if (limitViolation == SkuPurchaseLimitService.Violation.USER_LIMIT_EXCEEDED) {
            throw rejection(receiptId, listing.version().getId(), AcceptanceReceiptStatus.REJECTED_RETRYABLE,
                    AcceptanceRejectionCode.USER_PURCHASE_LIMIT_EXCEEDED);
        }
        if (limitViolation == SkuPurchaseLimitService.Violation.PHONE_BIND_TARGET_REQUIRED) {
            throw rejection(receiptId, listing.version().getId(), AcceptanceReceiptStatus.REJECTED_FINAL,
                    AcceptanceRejectionCode.PHONE_BIND_TARGET_REQUIRED);
        }
        if (limitViolation == SkuPurchaseLimitService.Violation.PHONE_BIND_LIMIT_EXCEEDED) {
            throw rejection(receiptId, listing.version().getId(), AcceptanceReceiptStatus.REJECTED_RETRYABLE,
                    AcceptanceRejectionCode.PHONE_BIND_PURCHASE_LIMIT_EXCEEDED);
        }
        // 启用每人限购但本单无可用手机号：仅记录中台内部备注，不向项目响应说明；订单仅创建一次，
        // 天然满足「同事务、重放/重试不重复追加」。
        String orderRemark = purchaseLimitService.isPersonalLimitSkipped(
                listing.sku(), command.targetAccountType(), command.fulfillmentAccountValue())
                ? PURCHASE_LIMIT_SKIP_REMARK : null;
        // 建单/资金前经独立新RR视图Reader复核真实项目资格：仅CONFIRMED通过，用其RoutingSnapshot
        // 持久化路由，不在本事务旧RR快照再次route。设计 §7.2「下单顺序」第6步、§3「最终受理」。
        CurrentSupplyEligibilityReader.ProjectEligibility eligibility =
                eligibilityReader.readProject(new RoutingInput(command.buyerId(), command.projectId(),
                        listing.sku().getId(), listing.sku().getFulfillmentType(), command.quantity(), now, Map.of()));
        if (eligibility.qualification() != SupplyQualification.CONFIRMED) {
            throw rejection(receiptId, listing.version().getId(), AcceptanceReceiptStatus.REJECTED_RETRYABLE,
                    AcceptanceRejectionCode.NO_ROUTABLE_SUPPLIER);
        }
        SupplierRoutingApplicationService.RoutingSnapshot routing = eligibility.routingSnapshot();
        if (!routing.decision().selected()) {
            throw rejection(receiptId, listing.version().getId(), AcceptanceReceiptStatus.REJECTED_RETRYABLE,
                    AcceptanceRejectionCode.NO_ROUTABLE_SUPPLIER);
        }
        RoutingCandidate selected = routing.decision().selectedCandidate();
        long buyerAmount = Math.multiplyExact(frozenQuote.distributionUnitPriceCents(), command.quantity());
        OrderSummaryFact summaryFact = new OrderSummaryFact(SupplyChainOrderTypes.ORDER_TYPE_CARD,
                SupplyChainOrderTypes.DETAIL_TYPE_FULFILLMENT, (short) 0,
                command.quantity(), 0L, null);
        OrderSummaryDecision summary = orderSummaryPolicyRegistry
                .require(SupplyChainOrderTypes.ORDER_TYPE_CARD).decide(summaryFact);

        SupplyChainOrderDO order = SupplyChainOrderDO.builder()
                .orderNo(businessNoGenerator.next(BusinessNoPrefixes.SC))
                .orderType(SupplyChainOrderTypes.ORDER_TYPE_CARD)
                .buyerId(command.buyerId()).projectId(command.projectId())
                .pricingQuoteId(frozenQuote.quoteId()).pricingVersionId(frozenQuote.pricingVersionId())
                .buyerCodeSnapshot(command.buyerCode())
                // CARD标准商品订单无购买人身份（052 CHECK约束固定为空）；真实履约目标账户类型独立冻结。
                .buyerNameSnapshot(command.buyerName())
                .fulfillmentAccountValue(command.fulfillmentAccountValue())
                .fulfillmentAccountType(command.targetAccountType()).remark(orderRemark)
                .buyerOrderNo(command.buyerOrderNo()).acceptanceReceiptId(receiptId)
                .projectCodeSnapshot(command.projectCode())
                .projectNameSnapshot(command.projectName())
                .displaySupplierId(selected.supplierId()).displaySupplierCode(selected.supplierCode())
                .displaySupplierName(selected.supplierName()).buyerSettlementAmount(buyerAmount)
                .salesUnitPriceCents(frozenQuote.salesUnitPriceCents())
                .productSubtotalCents(frozenQuote.productSubtotalCents())
                .serviceFeeCents(frozenQuote.serviceFeeCents())
                .userPayableAmountCents(frozenQuote.userPayableAmountCents())
                .refundableServiceFeeCents(frozenQuote.serviceFeeCents())
                .taxMetadataJson(frozenQuote.taxMetadataJson())
                .currency(Money.CURRENCY_CNY).platformSkuId(listing.sku().getId())
                .skuCodeSnapshot(listing.sku().getSkuCode()).skuNameSnapshot(listing.sku().getSkuName())
                .quantity(command.quantity()).buyerOrderDigest(command.buyerOrderDigest())
                .orderStatus(summary.orderStatus()).aftersaleStatus(summary.aftersaleStatus())
                .businessStage(summary.businessStage()).detailType(SupplyChainOrderTypes.DETAIL_TYPE_FULFILLMENT)
                .detailVersion(0L).requestFingerprint(command.requestFingerprint())
                .version(0L).createdAt(now).updatedAt(now).build();
        orderMapper.insert(order);
        pricingQuoteService.markConsumed(frozenQuote.quoteId(), order.getId(), now);
        inject(AcceptanceFailureInjector.Stage.ORDER_INSERTED);

        FulfillmentRequestDO fulfillmentRequest = FulfillmentRequestDO.builder()
                .requestNo(businessNoGenerator.next(BusinessNoPrefixes.FR)).orderId(order.getId())
                .buyerId(command.buyerId()).listingId(listing.listing().getId())
                .listingVersionId(listing.version().getId()).platformSkuId(listing.sku().getId())
                .targetSchemaCode(command.targetSchemaCode()).targetSchemaVersion(command.targetSchemaVersion())
                .targetCiphertext(command.targetCiphertext()).targetDigest(command.targetDigest())
                .targetKeyVersion(command.targetKeyVersion()).requestedQuantity(command.quantity())
                .fulfilledQuantity(0L).status(FulfillmentInitialStatuses.REQUEST_PENDING).aggregateVersion(0L)
                .createdAt(now).updatedAt(now).build();
        requestMapper.insert(fulfillmentRequest);
        if (orderMapper.attachDetail(order.getId(), fulfillmentRequest.getId(), 0L, now) != 1) {
            throw new IllegalStateException("Supply-chain order detail attachment lost invariant");
        }
        inject(AcceptanceFailureInjector.Stage.REQUEST_ATTACHED);

        RoutingPersistence routingPersistence = persistRouting(command, order, fulfillmentRequest, routing, now);
        inject(AcceptanceFailureInjector.Stage.ROUTING_RECORDED);

        BOrderDO bOrder = createBOrder(order, fulfillmentRequest, selected, now);
        bOrderMapper.insert(bOrder);
        if (requestMapper.activateBOrder(fulfillmentRequest.getId(), bOrder.getId(), now) != 1
                || routingRecordMapper.attachBOrder(routingPersistence.selectedRecordId(), bOrder.getId()) != 1) {
            throw new IllegalStateException("BOrder activation lost aggregate invariant");
        }
        if (orderMapper.advanceDetailVersion(order.getId(), 0L, 1L, now) != 1) {
            throw new IllegalStateException("Supply-chain order detail version lost monotonicity");
        }
        FulfillmentOperationDO operation = createOperation(command, bOrder, listing.sku(), now);
        operationMapper.insert(operation);
        inject(AcceptanceFailureInjector.Stage.B_ORDER_CREATED);
        scheduleHuashengPreparationWatchdogIfApplicable(operation, command.traceId(), now);

        rechargeFrequencyLifecycle.enqueue(new RechargeFrequencyLifecycle.EnqueueCommand(
                order.getId(), order.getOrderNo(), fulfillmentRequest.getId(), operation.getOperationNo(),
                command.buyerId(), command.projectId(), listing.sku().getId(),
                command.targetAccountType(), command.fulfillmentAccountValue()));

        FundReservationService.ReservationSnapshot reservation;
        try {
            reservation = fundReservationService.freeze(new FundReservationService.FreezeCommand(
                    command.buyerId(), FundReferenceType.CARD_ORDER.code(), fulfillmentRequest.getRequestNo(),
                    Money.cny(buyerAmount), order.getOrderNo(), command.traceId(), receipt.getReceiptNo()));
        } catch (IllegalStateException exception) {
            if (exception.getMessage() != null
                    && exception.getMessage().contains("account does not exist")) {
                log.warn("[履约][lifecycle] 资金账户未开户 buyerId={}, requestNo={}",
                        command.buyerId(), fulfillmentRequest.getRequestNo());
                throw rejection(receiptId, listing.version().getId(), AcceptanceReceiptStatus.REJECTED_RETRYABLE,
                        AcceptanceRejectionCode.BUYER_ACCOUNT_NOT_OPENED);
            }
            throw exception;
        }
        inject(AcceptanceFailureInjector.Stage.FUNDS_FROZEN);

        appendAcceptanceEvents(command, order, fulfillmentRequest, bOrder, operation, reservation, now);
        inject(AcceptanceFailureInjector.Stage.OUTBOX_APPENDED);

        if (receiptMapper.markAccepted(receiptId, listing.version().getId(), frozenQuote.quoteId(),
                order.getId(), order.getOrderNo(), fulfillmentRequest.getRequestNo(), now) != 1) {
            throw new IllegalStateException("Acceptance receipt was not PROCESSING at commit");
        }
        statusLogMapper.insert(OrderStatusLogDO.builder().orderId(order.getId()).detailVersion(1L)
                .toOrderStatus(summary.orderStatus()).toAftersaleStatus(summary.aftersaleStatus())
                .businessStage(summary.businessStage())
                .sourceEventType(OrderStatusAuditCodes.SOURCE_FULFILLMENT_ACCEPTED)
                .sourceEventNo(receipt.getReceiptNo()).reasonCode(OrderStatusAuditCodes.REASON_ACCEPTED)
                .traceId(command.traceId()).createdAt(now).build());
        log.info("[履约][lifecycle] accept 完成 receiptId={}, receiptNo={}, orderNo={}",
                receiptId, receipt.getReceiptNo(), order.getOrderNo());
        return snapshot(receiptId, false);
    }

    /**
     * 首冻前目录未就绪的可重试拒绝：不创建新报价，PROCESSING 回执落库 REJECTED_RETRYABLE 供条件恢复后
     * 按原冻结键续跑；已终态回执不重复改写。设计 §7.2「首冻前准备」。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public AcceptanceSnapshot rejectBeforeFreeze(long receiptId, Long listingVersionId,
                                                 AcceptanceRejectionCode code) {
        log.info("[履约][lifecycle] 首冻前拒绝 receiptId={}, code={}", receiptId, code);
        FulfillmentAcceptanceReceiptDO receipt = receiptMapper.selectByIdForUpdate(receiptId);
        if (receipt == null) {
            throw new IllegalStateException("Acceptance receipt disappeared before pre-freeze rejection");
        }
        if (receipt.getStatus() == AcceptanceReceiptStatus.PROCESSING.getCode()) {
            int updated = receiptMapper.markRejected(receipt.getId(), listingVersionId,
                    AcceptanceReceiptStatus.REJECTED_RETRYABLE.getCode(), code.name(), now());
            if (updated != 1) {
                throw new IllegalStateException("Acceptance rejection lost receipt state");
            }
        }
        return snapshot(receipt.getId(), false);
    }

    /**
     * 业务拒绝：PROCESSING 回执标记拒绝码后回放快照。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public AcceptanceSnapshot reject(BusinessRejection rejection) {
        log.info("[履约][lifecycle] reject 入口 receiptId={}, code={}, receiptStatus={}",
                rejection.receiptId(), rejection.rejectionCode(), rejection.receiptStatus());
        FulfillmentAcceptanceReceiptDO receipt = receiptMapper.selectByIdForUpdate(rejection.receiptId());
        if (receipt == null) {
            throw new IllegalStateException("Acceptance receipt disappeared before rejection");
        }
        if (receipt.getStatus() == AcceptanceReceiptStatus.PROCESSING.getCode()) {
            int updated = receiptMapper.markRejected(receipt.getId(), rejection.listingVersionId(),
                    rejection.receiptStatus().getCode(), rejection.rejectionCode().name(), now());
            if (updated != 1) {
                throw new IllegalStateException("Acceptance rejection lost receipt state");
            }
        }
        return snapshot(receipt.getId(), false);
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, readOnly = true)
    public AcceptanceSnapshot replay(long receiptId) {
        return snapshot(receiptId, true);
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, readOnly = true)
    public AcceptanceSnapshot replayByProjectKey(Long projectId, String buyerOrderNo) {
        FulfillmentAcceptanceReceiptDO receipt = receiptMapper.selectByProjectKey(projectId, buyerOrderNo);
        if (receipt == null) {
            throw new IllegalStateException("Acceptance receipt does not exist after duplicate claim");
        }
        return snapshot(receipt.getId(), true);
    }

    /**
     * 在事务建立普通查询快照前按固定顺序锁定 Listing 与平台 SKU。
     *
     * <p>MySQL REPEATABLE READ 会让锁前的一致性读固定历史快照；若随后才锁 SKU，等待事务仍可能看不到
     * 前序事务刚提交的订单。这里刻意只使用锁定读，并把后续所有可售与额度查询放在锁后。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#35-sku-限购计算并发与释放规则}。</p>
     */
    private LockedListing lockListingAndSku(PreparedAcceptance command) {
        CatalogIdentity identity = command.catalogIdentity();
        if (identity != null && (!Objects.equals(identity.buyerId(), command.buyerId())
                || !Objects.equals(identity.requestedCode(), command.buyerSkuCode()))) {
            throw new IllegalStateException("可信受理目录身份与应用命令不一致");
        }
        BuyerListingDO listing = catalogResolver.lockResolvedIdentity(identity);
        if (listing == null) {
            throw rejection(command.receiptIdHint(), null, AcceptanceReceiptStatus.REJECTED_RETRYABLE,
                    AcceptanceRejectionCode.LISTING_VERSION_EXPIRED);
        }
        PlatformSkuDO sku = skuMapper.selectByIdForUpdate(listing.getPlatformSkuId());
        if (sku == null) {
            throw rejection(command.receiptIdHint(), null, AcceptanceReceiptStatus.REJECTED_RETRYABLE,
                    AcceptanceRejectionCode.SKU_NOT_SALEABLE);
        }
        catalogResolver.verifyAssociationAfterSkuLock(identity, listing);
        return new LockedListing(listing, sku);
    }

    private ListingSnapshot requireSaleableListing(PreparedAcceptance command, LocalDateTime now,
                                                    LockedListing locked, boolean directProjectOrder) {
        BuyerListingDO listing = locked.listing();
        // 业务版本由服务端取得：按 Listing 当前发布版本锁定校验，不再接受调用方提交的商品版本。
        // 设计见 docs/superpowers/specs/2026-10-02-standard-acceptance-project-key-design.md#2-首次身份与重放优先。
        BuyerListingVersionDO version = requireCurrentListingVersion(listing);
        if (version == null || listing.getStatus() != BuyerListingStatus.PUBLISHED.getCode()
                || version.getValidFrom().isAfter(now)
                || (version.getValidTo() != null && !version.getValidTo().isAfter(now))) {
            throw rejection(command.receiptIdHint(), version == null ? null : version.getId(),
                    AcceptanceReceiptStatus.REJECTED_RETRYABLE,
                    AcceptanceRejectionCode.LISTING_VERSION_EXPIRED);
        }
        // HTTP 只认最终项目新视图，不以共享投影拒绝 MANUAL UNKNOWN；旧 RPC 保留原投影前置。见设计 §7.2/§8。
        if (!directProjectOrder) {
            BuyerListingAvailabilityDO availability = listingAvailabilityMapper.selectByListingId(listing.getId());
            if (availability == null || availability.getAvailability() != ListingAvailability.AVAILABLE.getCode()) {
                throw rejection(command.receiptIdHint(), version.getId(), AcceptanceReceiptStatus.REJECTED_RETRYABLE,
                        AcceptanceRejectionCode.INVENTORY_INSUFFICIENT);
            }
        }
        PlatformSkuDO sku;
        try {
            sku = productSellabilityService.requireSellable(locked.sku()).sku();
        } catch (ServiceException notSellable) {
            // 设计要求把不可售统一转为 SKU_NOT_SALEABLE 回执而非 HTTP 500，但必须保留原始错误码作为因果，
            // 见 docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#31-spu-与-sku-状态解耦与有效可售计算
            log.warn("平台 SKU 不可售，受理转拒绝回执 listingId={} platformSkuId={} errorCode={}",
                    listing.getId(), listing.getPlatformSkuId(), notSellable.getCode(), notSellable);
            throw rejection(command.receiptIdHint(), version.getId(), AcceptanceReceiptStatus.REJECTED_RETRYABLE,
                    AcceptanceRejectionCode.SKU_NOT_SALEABLE);
        }
        if (!command.targetSchemaCode().equals(sku.getTargetSchemaCode())
                || command.targetSchemaVersion() != sku.getTargetSchemaVersion()) {
            throw rejection(command.receiptIdHint(), version.getId(), AcceptanceReceiptStatus.REJECTED_FINAL,
                    AcceptanceRejectionCode.INVALID_TARGET);
        }
        return new ListingSnapshot(listing, version, sku);
    }

    private RoutingPersistence persistRouting(PreparedAcceptance command, SupplyChainOrderDO order,
                                              FulfillmentRequestDO request,
                                              SupplierRoutingApplicationService.RoutingSnapshot routing,
                                              LocalDateTime now) {
        List<RoutingCandidate> candidates = routing.decision().orderedCandidates();
        SupplierRoutingRoundDO round = SupplierRoutingRoundDO.builder()
                .routingRoundNo(businessNoGenerator.next(BusinessNoPrefixes.RR)).buyerId(command.buyerId())
                .routingSubjectType(RoutingSubjectType.STANDARD_REQUEST.code())
                // STANDARD_REQUEST 主体键统一为全局 orderNo，避免同一分销商不同项目同号时路由轮次撞键；
                // 历史 key 由 052 严格关联迁移，重路由/恢复沿用同一全局键。
                // 设计见 docs/superpowers/specs/2026-10-02-standard-acceptance-project-key-design.md#5-052增量历史清理与安全覆盖。
                .routingSubjectKey(order.getOrderNo())
                .routingType(RoutingType.STANDARD_PRODUCT.code()).orderId(order.getId())
                .fulfillmentRequestId(request.getId()).roundSequence(1)
                .policyId(routing.policyId()).policyVersionId(routing.policyVersionId())
                .matchedRuleId(routing.ruleId()).routingInputDigest(routingInputDigest(command, routing))
                .snapshotSchemaVersion(1).factSnapshotJson(factSnapshotJson(routing))
                .candidateCount(candidates.size()).decisionStatus(RoutingDecisionStatus.SELECTED.getCode())
                .decisionReasonCode(routing.decision().reasonCode())
                .decisionReasonMessage("按已发布路由规则选中首个合格供应商")
                .computedAt(now).createdAt(now).build();
        routingRoundMapper.insert(round);
        Long selectedRecordId = null;
        for (int index = 0; index < candidates.size(); index++) {
            RoutingCandidate candidate = candidates.get(index);
            boolean selected = candidate.equals(routing.decision().selectedCandidate());
            String reason = selected ? RoutingRecordDecisionCode.SELECTED.name()
                    : candidate.eligible()
                    ? RoutingRecordDecisionCode.ELIGIBLE_NOT_SELECTED.name()
                    : candidate.rejectionReasons().getFirst();
            SupplierRoutingRecordDO record = SupplierRoutingRecordDO.builder()
                    .routingRoundId(round.getId()).candidateSequence(index + 1)
                    .supplierId(candidate.supplierId()).supplierCodeSnapshot(candidate.supplierCode())
                    .supplierNameSnapshot(candidate.supplierName()).offerId(candidate.offerId())
                    .offerVersionId(candidate.offerVersionId()).connectorVersionId(candidate.connectorVersionId())
                    .inventorySourceId(candidate.inventorySourceId())
                    .configuredPriority(candidate.candidatePriority())
                    .availabilityClass(candidate.availabilityClass())
                    .supplierSettlementUnitPrice(candidate.supplierSettlementUnitPrice())
                    .factCapturedAt(candidate.latestFactAt()).checkSnapshotSchemaVersion(1)
                    .checkResultJson(checkResultJson(candidate)).eligibleFlag(candidate.eligible())
                    .decisionReasonCode(reason).decisionReasonMessage(reason)
                    .selectedFlag(selected ? Boolean.TRUE : null).createdAt(now).build();
            routingRecordMapper.insert(record);
            if (selected) {
                selectedRecordId = record.getId();
            }
        }
        if (selectedRecordId == null) {
            throw new IllegalStateException("Selected route was not persisted");
        }
        return new RoutingPersistence(round.getId(), selectedRecordId);
    }

    private BOrderDO createBOrder(SupplyChainOrderDO order, FulfillmentRequestDO request,
                                  RoutingCandidate selected, LocalDateTime now) {
        String bOrderNo = businessNoGenerator.next(BusinessNoPrefixes.BO);
        // billno 独立于内部 bOrderNo，便于多供应商路由与重放时供应商侧单号稳定唯一。
        String billno = businessNoGenerator.next(BusinessNoPrefixes.BILL);
        long expectedAmount = Math.multiplyExact(selected.supplierSettlementUnitPrice(), order.getQuantity());
        return BOrderDO.builder().bOrderNo(bOrderNo).fulfillmentRequestId(request.getId())
                .orderId(order.getId()).attemptNo(1).supplierId(selected.supplierId())
                .offerId(selected.offerId()).offerVersionId(selected.offerVersionId())
                .connectorVersionId(selected.connectorVersionId()).inventorySourceId(selected.inventorySourceId())
                .billno(billno).idempotencyKey(FulfillmentIdempotencyKeys.bOrderFulfill(
                        selected.supplierCode(), bOrderNo))
                .requestedQuantity(order.getQuantity()).fulfilledQuantity(0L)
                .supplierSettlementUnitPrice(selected.supplierSettlementUnitPrice())
                .expectedSupplierSettlementAmount(expectedAmount).currency(Money.CURRENCY_CNY)
                .fulfillmentStatus(FulfillmentInitialStatuses.B_ORDER_PENDING)
                .settlementStatus(FulfillmentInitialStatuses.B_ORDER_SETTLEMENT_PENDING)
                .activeFlag(Boolean.TRUE).version(0L).createdAt(now).updatedAt(now).build();
    }

    private FulfillmentOperationDO createOperation(PreparedAcceptance command, BOrderDO bOrder, PlatformSkuDO sku, LocalDateTime now) {
        PublishedConnectorConfigurationResolver connectorResolver = connectorResolverProvider != null
                ? connectorResolverProvider.getIfAvailable() : null;
        PublishedConnectorConfiguration connectorConfig = null;
        if (connectorResolver != null && bOrder.getConnectorVersionId() != null) {
            try {
                connectorConfig = connectorResolver.resolve(bOrder.getConnectorVersionId());
            } catch (Exception e) {
                log.warn("[履约][lifecycle] 连接器版本解析失败 connectorVersionId={}, error={}",
                        bOrder.getConnectorVersionId(), e.getMessage());
            }
        }

        if (connectorConfig != null && HuashengOrderV1Metadata.TYPE.equals(connectorConfig.adapterType())) {
            return createHuashengInitialOperation(command, bOrder, connectorConfig, now);
        }

        AdapterStepCode step = AdapterStepCode.forFulfillmentType(sku.getFulfillmentType());
        String idempotencyKey = FulfillmentIdempotencyKeys.operation(bOrder.getBOrderNo(), 1);
        return FulfillmentOperationDO.builder().operationNo(businessNoGenerator.next(BusinessNoPrefixes.OP))
                .bOrderId(bOrder.getId()).operationKind(FulfillmentOperationKind.FULFILL.name())
                .adapterStepCode(step.name())
                .sequenceNo(1).idempotencyKey(idempotencyKey)
                .configDigest(sha256((idempotencyKey + "|" + bOrder.getOfferVersionId()
                        + "|" + bOrder.getConnectorVersionId()).getBytes(StandardCharsets.UTF_8)))
                .status(FulfillmentInitialStatuses.OPERATION_PENDING).attemptCount(0)
                .createdAt(now).updatedAt(now).version(0L).build();
    }

    private FulfillmentOperationDO createHuashengInitialOperation(PreparedAcceptance command, BOrderDO bOrder,
                                                                   PublishedConnectorConfiguration connectorConfig,
                                                                   LocalDateTime now) {
        HuashengCheckpointCodec codec = checkpointCodecProvider != null ? checkpointCodecProvider.getIfAvailable() : null;
        if (codec == null) {
            throw new IllegalStateException("HuashengCheckpointCodec 不可用，无法执行华昇首单受理");
        }
        SupplierOfferVersionMapper offerVersionMapper = offerVersionMapperProvider != null ? offerVersionMapperProvider.getIfAvailable() : null;
        if (offerVersionMapper == null) {
            throw new IllegalStateException("SupplierOfferVersionMapper 不可用，无法执行华昇首单受理");
        }
        SupplierOfferVersionDO offerVersion = offerVersionMapper.selectById(bOrder.getOfferVersionId());
        if (offerVersion == null || offerVersion.getExternalSkuCode() == null || offerVersion.getExternalSkuCode().isBlank()) {
            throw new IllegalStateException("华昇 Offer 外部商品编码缺失: offerVersionId=" + bOrder.getOfferVersionId());
        }
        long couponId;
        try {
            couponId = Long.parseLong(offerVersion.getExternalSkuCode().trim());
            if (couponId <= 0) {
                throw new IllegalArgumentException("华昇 couponId 必须为正整数");
            }
        } catch (NumberFormatException e) {
            throw new IllegalStateException("华昇 externalSkuCode 无法解析为正整数 couponId: " + offerVersion.getExternalSkuCode());
        }

        if (bOrder.getBillno() == null || bOrder.getBillno().isBlank()) {
            throw new IllegalStateException("bOrder.billno 不能为空");
        }

        int expireTime = 300;
        if (connectorConfig.extraParameters() != null && connectorConfig.extraParameters().containsKey("expireTime")) {
            String raw = connectorConfig.extraParameters().get("expireTime");
            if (raw != null && !raw.isBlank()) {
                try {
                    expireTime = Integer.parseInt(raw.trim());
                } catch (NumberFormatException e) {
                    log.warn("[履约][lifecycle] 华昇 expireTime 解析异常 raw={}, 采用默认300", raw);
                }
            }
        }
        if (expireTime < HuashengFlowCodes.EXPIRE_TIME_MIN_SECONDS || expireTime > HuashengFlowCodes.EXPIRE_TIME_MAX_SECONDS) {
            expireTime = 300;
        }

        String outMobile = null;
        if (connectorConfig.extraParameters() != null && connectorConfig.extraParameters().containsKey("outMobile")) {
            outMobile = connectorConfig.extraParameters().get("outMobile");
        }
        if ((outMobile == null || outMobile.isBlank()) && command.fulfillmentAccountValue() != null && !command.fulfillmentAccountValue().isBlank()) {
            outMobile = command.fulfillmentAccountValue();
        }
        if (outMobile != null && outMobile.isBlank()) {
            outMobile = null;
        }

        FulfillmentCallbackProperties callbackProperties = callbackPropertiesProvider != null ? callbackPropertiesProvider.getIfAvailable() : null;
        String base = callbackProperties != null ? callbackProperties.getPublicCallbackBaseUrl() : null;
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("publicCallbackBaseUrl 不能为空，无法生成华昇回调地址");
        }
        String normalizedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String callbackPath = connectorConfig.callbackPath() != null && !connectorConfig.callbackPath().isBlank()
                ? connectorConfig.callbackPath() : "/callbacks/suppliers/huasheng";
        String normalizedPath = callbackPath.startsWith("/") ? callbackPath : "/" + callbackPath;
        if (!normalizedPath.endsWith("/")) {
            normalizedPath = normalizedPath + "/";
        }
        String notifyUrl = normalizedBase + normalizedPath + bOrder.getConnectorVersionId();

        String operationNo = businessNoGenerator.next(BusinessNoPrefixes.OP);
        String idempotencyKey = FulfillmentIdempotencyKeys.operation(bOrder.getBOrderNo(), 1);
        byte[] configDigest = sha256((idempotencyKey + "|" + bOrder.getOfferVersionId()
                + "|" + bOrder.getConnectorVersionId()).getBytes(StandardCharsets.UTF_8));

        HuashengCheckpoint.Binding binding = new HuashengCheckpoint.Binding(
                bOrder.getId(),
                bOrder.getBOrderNo(),
                operationNo,
                1,
                AdapterStepCode.PLACE_ORDER,
                bOrder.getConnectorVersionId(),
                bOrder.getOfferVersionId(),
                configDigest
        );
        HuashengCheckpoint.PlaceParameters placeParams = new HuashengCheckpoint.PlaceParameters(
                bOrder.getBillno(),
                couponId,
                HuashengFlowCodes.TYPE201_ORDER_QUANTITY,
                expireTime,
                outMobile,
                notifyUrl
        );
        HuashengCheckpoint.Progress progress = new HuashengCheckpoint.Progress(
                0, 0, 0, null, null, command.traceId()
        );
        HuashengCheckpoint checkpoint = new HuashengCheckpoint(
                binding, placeParams, null, null, progress, null
        );
        HuashengCheckpointCodec.Stored stored = codec.encrypt(checkpoint);

        return FulfillmentOperationDO.builder()
                .operationNo(operationNo)
                .bOrderId(bOrder.getId())
                .operationKind(FulfillmentOperationKind.FULFILL.name())
                .adapterStepCode(AdapterStepCode.PLACE_ORDER.name())
                .sequenceNo(1)
                .idempotencyKey(idempotencyKey)
                .configDigest(configDigest)
                .status(FulfillmentInitialStatuses.OPERATION_PENDING)
                .attemptCount(0)
                .checkpointSchemaVersion(stored.schemaVersion())
                .checkpointKeyVersion(stored.keyVersion())
                .checkpointCiphertext(stored.ciphertext())
                .executionGeneration(0L)
                .recoveryCount(0)
                .createdAt(now)
                .updatedAt(now)
                .version(0L)
                .build();
    }

    private void scheduleHuashengPreparationWatchdogIfApplicable(FulfillmentOperationDO operation, String originTraceId, LocalDateTime now) {
        if (AdapterStepCode.PLACE_ORDER.name().equals(operation.getAdapterStepCode())
                && operation.getCheckpointCiphertext() != null) {
            PersistentTimer persistentTimer = persistentTimerProvider != null ? persistentTimerProvider.getIfAvailable() : null;
            if (persistentTimer != null) {
                ObjectNode prepPayload = objectMapper.createObjectNode();
                prepPayload.put("operationNo", operation.getOperationNo());
                if (originTraceId != null) {
                    prepPayload.put("originTraceId", originTraceId);
                }
                persistentTimer.scheduleOnce(
                        HuashengFlowCodes.PREPARATION_WATCHDOG_TASK,
                        "HS:PREP:" + operation.getOperationNo(),
                        prepPayload,
                        databaseClock.toInstant(now.plusMinutes(HuashengFlowPolicy.PREPARATION_WATCHDOG_MINUTES))
                );
            }
        }
    }

    private void appendAcceptanceEvents(PreparedAcceptance command, SupplyChainOrderDO order,
                                        FulfillmentRequestDO request, BOrderDO bOrder,
                                        FulfillmentOperationDO operation,
                                        FundReservationService.ReservationSnapshot reservation,
                                        LocalDateTime now) {
        outboxRepository.appendLocal(FulfillmentOutboxCodes.AGGREGATE_B_ORDER, bOrder.getBOrderNo(), 0,
                FulfillmentOutboxCodes.EVENT_FULFILLMENT_OPERATION_READY,
                FulfillmentOutboxCodes.TARGET_FULFILLMENT_OPERATION_WORKER,
                Map.of("operationNo", operation.getOperationNo(), "bOrderNo", bOrder.getBOrderNo()),
                command.traceId(), order.getOrderNo(), command.buyerOrderNo(), now);
        outboxRepository.appendLocal(FulfillmentOutboxCodes.AGGREGATE_SUPPLY_CHAIN_ORDER, order.getOrderNo(), 1,
                FulfillmentOutboxCodes.EVENT_SUPPLY_CHAIN_ORDER_CHANGED,
                FulfillmentOutboxCodes.TARGET_SUPPLY_CHAIN_ORDER_EVENT,
                Map.of("orderNo", order.getOrderNo(),
                        "orderStatus", FulfillmentOutboxCodes.PAYLOAD_ORDER_STATUS_PROCESSING),
                command.traceId(), order.getOrderNo(), operation.getOperationNo(), now);
        outboxRepository.appendLocal(FulfillmentOutboxCodes.AGGREGATE_BUYER_ORDER, order.getOrderNo(), 1,
                FulfillmentOutboxCodes.EVENT_BUYER_ORDER_SUMMARY_CHANGED,
                FulfillmentOutboxCodes.TARGET_BUYER_EVENT_RPC,
                Map.of("orderNo", order.getOrderNo(), "buyerOrderNo", command.buyerOrderNo(),
                        "fulfillmentRequestNo", request.getRequestNo(),
                        "reservationNo", reservation.reservationNo()),
                command.traceId(), order.getOrderNo(), operation.getOperationNo(), now);
    }

    /**
     * 回执快照组装：orderStatus 读取订单表当前真实状态（未建单为 null），首次与重放返回一致，
     * 不再恒写 PROCESSING。重放返回真实订单状态见
     * docs/superpowers/specs/2026-10-02-standard-acceptance-project-key-design.md#3-回执竞争、重试和真实返回。
     */
    private AcceptanceSnapshot snapshot(long receiptId, boolean replay) {
        FulfillmentAcceptanceReceiptDO receipt = receiptMapper.selectById(receiptId);
        if (receipt == null) {
            throw new IllegalStateException("Acceptance receipt does not exist");
        }
        SupplyChainOrderDO order = receipt.getResultOrderId() == null
                ? null : orderMapper.selectById(receipt.getResultOrderId());
        return new AcceptanceSnapshot(receipt.getReceiptNo(), receipt.getBuyerOrderNo(),
                receipt.getStatus(), receipt.getVersion(), receipt.getResultOrderNo(),
                receipt.getResultFulfillmentRequestNo(), receipt.getRejectionCode(),
                order == null ? null : order.getDisplaySupplierCode(),
                order == null ? null : order.getDisplaySupplierName(), receipt.getAcceptedAt(), replay,
                order == null ? null : order.getOrderStatus());
    }

    /**
     * 服务端解析本次尝试的商品业务版本号（回执 listing_version_no）。
     * 未发布目录没有商业版本，本次尝试保留真实 NULL；非空 current 指针损坏属于系统故障。
     * 设计见 docs/superpowers/specs/2026-10-04-buyer-selection-acceptance-catalog-bridge-design.md#10-draft稳定响应与管理端可见结果。
     */
    private Integer resolveAttemptListingVersionNo(PreparedAcceptance command, BuyerListingDO gateListing) {
        BuyerListingVersionDO version = requireCurrentListingVersion(gateListing);
        return version == null ? null : version.getVersionNo();
    }

    /**
     * claim 与最终受理共享同一完整性检查，避免最终锁定后把损坏的非空来源降级为可重试草稿。
     * 仅目录不存在或 current 为空表示尚未发布，不得把跨目录版本或非法业务版本号当成缺条件。
     * 设计见 docs/superpowers/specs/2026-10-04-buyer-selection-acceptance-catalog-bridge-design.md#10-draft稳定响应与管理端可见结果。
     */
    private BuyerListingVersionDO requireCurrentListingVersion(BuyerListingDO gateListing) {
        if (gateListing == null || gateListing.getCurrentVersionId() == null) {
            return null;
        }
        BuyerListingVersionDO version = listingVersionMapper.selectById(gateListing.getCurrentVersionId());
        if (version == null || !Objects.equals(version.getListingId(), gateListing.getId())
                || version.getVersionNo() == null || version.getVersionNo() < 1) {
            throw new IllegalStateException("Buyer listing version snapshot is broken: listingId="
                    + gateListing.getId());
        }
        return version;
    }

    private void requireProcessingReceipt(FulfillmentAcceptanceReceiptDO receipt,
                                          PreparedAcceptance command) {
        if (receipt == null || receipt.getStatus() != AcceptanceReceiptStatus.PROCESSING.getCode()
                || !Objects.equals(receipt.getFingerprintVersion(), command.fingerprintVersion())
                || !Arrays.equals(receipt.getRequestFingerprint(), command.requestFingerprint())) {
            throw new IdempotencyConflictException();
        }
        command.receiptIdHint(receipt.getId());
    }

    private boolean isReleasedQuotaRetry(FulfillmentAcceptanceReceiptDO receipt, PreparedAcceptance command) {
        return receipt.getStatus() == AcceptanceReceiptStatus.REJECTED_RETRYABLE.getCode()
                && (AcceptanceRejectionCode.USER_PURCHASE_LIMIT_EXCEEDED.name().equals(receipt.getRejectionCode())
                || AcceptanceRejectionCode.PHONE_BIND_PURCHASE_LIMIT_EXCEEDED.name().equals(receipt.getRejectionCode()))
                && command.previousReceiptVersion() != null
                && Objects.equals(command.previousReceiptVersion(), receipt.getVersion());
    }

    private void reopenRetryable(FulfillmentAcceptanceReceiptDO receipt, PreparedAcceptance command) {
        log.info("[履约][lifecycle] reopenRetryable 重开受理回执 receiptId={}, version={}",
                receipt.getId(), receipt.getVersion());
        // 重开不再要求调用方更换 Listing 版本：过渡期保留回执原 listing_version_no 不变
        // （服务端尝试版本的独立列承载见 052 增量），仅刷新格式 2 请求指纹。
        int updated = receiptMapper.reopenRetryable(receipt.getId(), receipt.getVersion(),
                receipt.getListingVersionNo(), command.requestFingerprint(), now());
        if (updated != 1) {
            throw new IdempotencyConflictException();
        }
    }

    private byte[] routingInputDigest(PreparedAcceptance command,
                                      SupplierRoutingApplicationService.RoutingSnapshot routing) {
        String canonical = command.buyerCode() + '|' + command.buyerSkuCode() + '|'
                + command.quantity() + '|' + routing.policyVersionNo() + '|'
                + HexFormat.of().formatHex(routing.configDigest());
        return sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private String factSnapshotJson(SupplierRoutingApplicationService.RoutingSnapshot routing) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (RoutingCandidate candidate : routing.decision().orderedCandidates()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("supplierCode", candidate.supplierCode());
            item.put("eligible", candidate.eligible());
            item.put("availabilityClass", candidate.availabilityClass());
            item.put("rejectionReasons", candidate.rejectionReasons());
            candidates.add(item);
        }
        return writeJson(Map.of("schemaVersion", 1, "candidates", candidates));
    }

    private String checkResultJson(RoutingCandidate candidate) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inventory", candidate.inventoryFactStatus());
        result.put("balance", candidate.balanceFactStatus());
        result.put("health", candidate.healthFactStatus());
        result.put("reasons", candidate.rejectionReasons());
        return writeJson(result);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Routing audit JSON serialization failed", exception);
        }
    }

    private void inject(AcceptanceFailureInjector.Stage stage) {
        AcceptanceFailureInjector injector = failureInjector.getIfAvailable();
        if (injector != null) {
            injector.after(stage);
        }
    }

    private static BusinessRejection rejection(long receiptId, Long listingVersionId,
                                               AcceptanceReceiptStatus status,
                                               AcceptanceRejectionCode code) {
        return new BusinessRejection(receiptId, listingVersionId, status, code);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private LocalDateTime now() {
        return databaseClock.now();
    }

    public record ClaimResult(long receiptId, boolean executeAcceptance, boolean replay) {
    }

    /** 受理回执快照：orderStatus 为订单表当前真实状态（未建单为 null），供响应返回真实状态。 */
    public record AcceptanceSnapshot(String receiptNo, String buyerOrderNo, short status,
                                     long receiptVersion, String orderNo, String fulfillmentRequestNo,
                                     String rejectionCode, String supplierCode, String supplierName,
                                     LocalDateTime acceptedAt, boolean idempotentReplay, Short orderStatus) {
    }

    /**
     * 加密与请求指纹准备阶段使用的平台 SKU Schema 快照（含服务端解析的商品身份）。
     * platformSkuId 供格式 2 指纹的「服务端冻结商品身份（可解析时）」成分；不可解析时为 null。
     */
    public record TargetSchemaSnapshot(Long platformSkuId, String schemaCode, int schemaVersion, boolean available,
                                       CatalogIdentity catalogIdentity) {
        public TargetSchemaSnapshot(Long platformSkuId, String schemaCode, int schemaVersion, boolean available) {
            this(platformSkuId, schemaCode, schemaVersion, available, null);
        }
        private static TargetSchemaSnapshot unavailable() {
            return new TargetSchemaSnapshot(null, "", 0, false);
        }
    }

    /**
     * 可信标准受理命令（双入口共享）：已删除购买人身份、支付时间、调用方商品版本与报价 token；
     * 服务端冻结商品身份与报价 id 由 accept 阶段的报价消费上下文承载，不在准备期伪造。
     * 设计见 docs/superpowers/specs/2026-10-02-standard-acceptance-project-key-design.md#1-双入口与共享命令。
     */
    public static final class PreparedAcceptance {
        private final Long projectId;
        private final String projectCode;
        private final String projectName;
        private final Long buyerId;
        private final String buyerCode;
        private final String buyerName;
        private final String buyerOrderNo;
        /** 实际 MOBILE 目标的规范化手机号（履约目标，非购买人身份）；非 MOBILE 为空。 */
        private final String fulfillmentAccountValue;
        /** 来自已校验实际target的账户类型，用于区分非MOBILE与非法MOBILE，不从任何请求身份推断。 */
        private final String targetAccountType;
        private final String buyerSkuCode;
        private final long quantity;
        private final String targetSchemaCode;
        private final int targetSchemaVersion;
        private final byte[] targetCiphertext;
        private final byte[] targetDigest;
        private final String targetKeyVersion;
        private final boolean targetValid;
        private final byte[] invariantFingerprint;
        private final byte[] requestFingerprint;
        private final byte[] buyerOrderDigest;
        private final Long previousReceiptVersion;
        private final String traceId;
        private final CatalogIdentity catalogIdentity;
        private final short fingerprintVersion;
        private final String requestedPlatformSkuCode;
        private final Long frozenPlatformSkuId;
        private long receiptIdHint;

        public PreparedAcceptance(Long projectId, String projectCode, String projectName,
                                  Long buyerId, String buyerCode, String buyerName,
                                  String buyerOrderNo, String fulfillmentAccountValue, String targetAccountType,
                                  String buyerSkuCode, long quantity,
                                  String targetSchemaCode, int targetSchemaVersion,
                                  byte[] targetCiphertext, byte[] targetDigest,
                                  String targetKeyVersion, boolean targetValid,
                                  byte[] invariantFingerprint, byte[] requestFingerprint,
                                  byte[] buyerOrderDigest, Long previousReceiptVersion, String traceId) {
            this(projectId, projectCode, projectName, buyerId, buyerCode, buyerName, buyerOrderNo,
                    fulfillmentAccountValue, targetAccountType, buyerSkuCode, quantity, targetSchemaCode,
                    targetSchemaVersion, targetCiphertext, targetDigest, targetKeyVersion, targetValid,
                    invariantFingerprint, requestFingerprint, buyerOrderDigest, previousReceiptVersion, traceId, null);
        }

        public PreparedAcceptance(Long projectId, String projectCode, String projectName,
                                  Long buyerId, String buyerCode, String buyerName,
                                  String buyerOrderNo, String fulfillmentAccountValue, String targetAccountType,
                                  String buyerSkuCode, long quantity, String targetSchemaCode, int targetSchemaVersion,
                                  byte[] targetCiphertext, byte[] targetDigest, String targetKeyVersion, boolean targetValid,
                                  byte[] invariantFingerprint, byte[] requestFingerprint,
                                  byte[] buyerOrderDigest, Long previousReceiptVersion, String traceId, CatalogIdentity catalogIdentity) {
            this(projectId, projectCode, projectName, buyerId, buyerCode, buyerName, buyerOrderNo,
                    fulfillmentAccountValue, targetAccountType, buyerSkuCode, quantity, targetSchemaCode,
                    targetSchemaVersion, targetCiphertext, targetDigest, targetKeyVersion, targetValid,
                    invariantFingerprint, requestFingerprint, buyerOrderDigest, previousReceiptVersion, traceId,
                    catalogIdentity, FINGERPRINT_FORMAT_NO_USER, null, null);
        }

        /**
         * 格式3必须显式传播原码与真实冻结SKU，不允许缺字段降级；旧构造始终使用格式2。
         * 设计见 docs/superpowers/specs/2026-10-12-platform-sku-order-contract-design.md#42-共享命令及执行分支。
         */
        public PreparedAcceptance(Long projectId, String projectCode, String projectName,
                                  Long buyerId, String buyerCode, String buyerName,
                                  String buyerOrderNo, String fulfillmentAccountValue, String targetAccountType,
                                  String buyerSkuCode, long quantity, String targetSchemaCode, int targetSchemaVersion,
                                  byte[] targetCiphertext, byte[] targetDigest, String targetKeyVersion, boolean targetValid,
                                  byte[] invariantFingerprint, byte[] requestFingerprint,
                                  byte[] buyerOrderDigest, Long previousReceiptVersion, String traceId, CatalogIdentity catalogIdentity,
                                  short fingerprintVersion, String requestedPlatformSkuCode, Long frozenPlatformSkuId) {
            if (fingerprintVersion != FINGERPRINT_FORMAT_NO_USER
                    && fingerprintVersion != StandardAcceptanceFingerprintPolicy.PLATFORM_SKU_FORMAT_VERSION
                    || fingerprintVersion == FINGERPRINT_FORMAT_NO_USER && requestedPlatformSkuCode != null
                    || fingerprintVersion == StandardAcceptanceFingerprintPolicy.PLATFORM_SKU_FORMAT_VERSION
                    && (requestedPlatformSkuCode == null || requestedPlatformSkuCode.isBlank()
                    || frozenPlatformSkuId == null || frozenPlatformSkuId <= 0
                    || buyerSkuCode == null || buyerSkuCode.isBlank())) {
                throw new IllegalArgumentException("受理请求格式与冻结商品身份不一致");
            }
            this.projectId = projectId;
            this.projectCode = projectCode;
            this.projectName = projectName;
            this.buyerId = buyerId;
            this.buyerCode = buyerCode;
            this.buyerName = buyerName;
            this.buyerOrderNo = buyerOrderNo;
            this.fulfillmentAccountValue = fulfillmentAccountValue;
            this.targetAccountType = targetAccountType;
            this.buyerSkuCode = buyerSkuCode;
            this.quantity = quantity;
            this.targetSchemaCode = targetSchemaCode;
            this.targetSchemaVersion = targetSchemaVersion;
            this.targetCiphertext = targetCiphertext == null ? null : targetCiphertext.clone();
            this.targetDigest = targetDigest.clone();
            this.targetKeyVersion = targetKeyVersion;
            this.targetValid = targetValid;
            this.invariantFingerprint = invariantFingerprint.clone();
            this.requestFingerprint = requestFingerprint.clone();
            this.buyerOrderDigest = buyerOrderDigest.clone();
            this.previousReceiptVersion = previousReceiptVersion;
            this.traceId = traceId;
            this.catalogIdentity = catalogIdentity;
            this.fingerprintVersion = fingerprintVersion;
            this.requestedPlatformSkuCode = requestedPlatformSkuCode;
            this.frozenPlatformSkuId = frozenPlatformSkuId;
        }

        public Long projectId() { return projectId; }
        public String projectCode() { return projectCode; }
        public String projectName() { return projectName; }
        public Long buyerId() { return buyerId; }
        public String buyerCode() { return buyerCode; }
        public String buyerName() { return buyerName; }
        public String buyerOrderNo() { return buyerOrderNo; }
        public String fulfillmentAccountValue() { return fulfillmentAccountValue; }
        public String targetAccountType() { return targetAccountType; }
        public String buyerSkuCode() { return buyerSkuCode; }
        public long quantity() { return quantity; }
        public String targetSchemaCode() { return targetSchemaCode; }
        public int targetSchemaVersion() { return targetSchemaVersion; }
        public byte[] targetCiphertext() { return targetCiphertext == null ? null : targetCiphertext.clone(); }
        public byte[] targetDigest() { return targetDigest.clone(); }
        public String targetKeyVersion() { return targetKeyVersion; }
        public boolean targetValid() { return targetValid; }
        public byte[] invariantFingerprint() { return invariantFingerprint.clone(); }
        public byte[] requestFingerprint() { return requestFingerprint.clone(); }
        public byte[] buyerOrderDigest() { return buyerOrderDigest.clone(); }
        public Long previousReceiptVersion() { return previousReceiptVersion; }
        public String traceId() { return traceId; }
        public CatalogIdentity catalogIdentity() { return catalogIdentity; }
        public short fingerprintVersion() { return fingerprintVersion; }
        public String requestedPlatformSkuCode() { return requestedPlatformSkuCode; }
        public Long frozenPlatformSkuId() { return frozenPlatformSkuId; }
        public boolean platformSkuRequest() { return fingerprintVersion == StandardAcceptanceFingerprintPolicy.PLATFORM_SKU_FORMAT_VERSION; }
        public long receiptIdHint() { return receiptIdHint; }
        private void receiptIdHint(long value) { this.receiptIdHint = value; }
    }

    public static final class BusinessRejection extends RuntimeException {
        private final long receiptId;
        private final Long listingVersionId;
        private final AcceptanceReceiptStatus receiptStatus;
        private final AcceptanceRejectionCode rejectionCode;

        public BusinessRejection(long receiptId, Long listingVersionId,
                          AcceptanceReceiptStatus receiptStatus, AcceptanceRejectionCode rejectionCode) {
            this(receiptId, listingVersionId, receiptStatus, rejectionCode, null);
        }

        public BusinessRejection(long receiptId, Long listingVersionId,
                          AcceptanceReceiptStatus receiptStatus, AcceptanceRejectionCode rejectionCode, Throwable cause) {
            super(rejectionCode.name(), cause, false, false);
            this.receiptId = receiptId;
            this.listingVersionId = listingVersionId;
            this.receiptStatus = receiptStatus;
            this.rejectionCode = rejectionCode;
        }

        public long receiptId() { return receiptId; }
        public Long listingVersionId() { return listingVersionId; }
        public AcceptanceReceiptStatus receiptStatus() { return receiptStatus; }
        public AcceptanceRejectionCode rejectionCode() { return rejectionCode; }
    }

    public static final class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException() {
            super("Acceptance business key payload conflicts with the original request");
        }
    }

    /**
     * 项目键并发首创竞争信号：仅由 claim 的项目永久键唯一冲突抛出，携带原始 DuplicateKeyException
     * 作为因（保留数据库错误码与消息）。Facade 只对它做新事务项目键重探测，不捕获任意键冲突盲回放。
     */
    public static final class ProjectKeyClaimRaceException extends RuntimeException {
        public ProjectKeyClaimRaceException(DuplicateKeyException cause) {
            super("Acceptance project key race on concurrent first claim", cause);
        }
    }

    private record ListingSnapshot(BuyerListingDO listing, BuyerListingVersionDO version,
                                   PlatformSkuDO sku) {
    }

    private record LockedListing(BuyerListingDO listing, PlatformSkuDO sku) {
    }

    private record RoutingPersistence(Long roundId, Long selectedRecordId) {
    }
}
