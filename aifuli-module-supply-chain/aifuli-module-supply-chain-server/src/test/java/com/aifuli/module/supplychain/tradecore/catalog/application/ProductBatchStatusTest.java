package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService.BatchStatusCommand;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService.BatchStatusFailedItem;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService.BatchStatusResult;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentTargetSchemaRegistry;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BrandMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingAvailabilityMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuPricingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductCategoryMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityType;
import com.aifuli.module.supplychain.tradecore.catalog.domain.UnshelveMode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PLATFORM_SKU_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PLATFORM_SPU_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_BATCH_INPUT_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_STATUS_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_STATUS_TRANSITION_NOT_ALLOWED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 批量上下架命令测试。
 * <p>设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#4110-批量上下架接口put-supply-chainproductsbatch-status}。
 * <p>覆盖：
 * <ul>
 *   <li>D1 逐项独立事务：模板配置为 {@code REQUIRES_NEW} + {@code READ_COMMITTED}，每项各自 commit，失败项各自 rollback；</li>
 *   <li>D2 系统异常不伪装为业务拒绝：非 {@code ServiceException} 原样上抛，且已提交项不回滚；</li>
 *   <li>D3 入参整体拒绝：未选择商品、编码空白、超过单次上限，均在开启任何事务前拒绝；</li>
 *   <li>回执不变量：{@code succeeded + failed} 恒等于入参编码总数。</li>
 * </ul>
 * <p><b>本单测的证明边界</b>：注入的是 mock {@link PlatformTransactionManager}，因此这里跑的是真实
 * {@code TransactionTemplate} 代码路径，能证明「模板被配置成什么传播行为」与「每项各调用一次
 * commit/rollback」的调用序列；<b>数据库层面真正的隔离与回滚效果</b>（第 N 项失败时前 N-1 项已落库）
 * 由 {@code ProductBatchStatusTransactionIntegrationTest} 在真实 MySQL 下证明，二者不可互相替代。
 */
class ProductBatchStatusTest {

    private final SupplyChainDatabaseClock clock = mock(SupplyChainDatabaseClock.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final BrandMapper brandMapper = mock(BrandMapper.class);
    private final ProductCategoryMapper categoryMapper = mock(ProductCategoryMapper.class);
    private final PlatformSpuMapper spuMapper = mock(PlatformSpuMapper.class);
    private final PlatformSkuMapper skuMapper = mock(PlatformSkuMapper.class);
    private final PlatformSkuPricingMapper platformSkuPricingMapper = mock(PlatformSkuPricingMapper.class);
    private final BuyerMapper buyerMapper = mock(BuyerMapper.class);
    private final BuyerListingMapper buyerListingMapper = mock(BuyerListingMapper.class);
    private final BuyerListingAvailabilityMapper availabilityMapper = mock(BuyerListingAvailabilityMapper.class);
    private final BusinessNoGenerator generator = mock(BusinessNoGenerator.class);
    private final BrandAdminService brandAdminService = mock(BrandAdminService.class);
    private final CategoryCommandService categoryCommandService = mock(CategoryCommandService.class);
    private final ProductTypeValidationService productTypeValidationService = mock(ProductTypeValidationService.class);
    private final ProductClassificationValidationService productClassificationValidationService =
            new ProductClassificationValidationService(categoryMapper);
    private final ProductSellabilityService sellabilityService = mock(ProductSellabilityService.class);
    private final ProductDeletionValidator deletionValidator = mock(ProductDeletionValidator.class);
    private final FulfillmentTargetSchemaRegistry targetSchemaRegistry = mock(FulfillmentTargetSchemaRegistry.class);
    private final PlatformSkuSchemaRegistry skuSchemaRegistry = new PlatformSkuSchemaRegistry(targetSchemaRegistry);

    /**
     * 供应链独立事务管理器。服务在构造器内据此构建 {@code TransactionTemplate}，
     * 故此处 mock 管理器而非模板本身：真实模板逻辑得以执行，并可捕获其传入的
     * {@link TransactionDefinition} 断言传播行为与隔离级别。
     */
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final TransactionStatus transactionStatus = mock(TransactionStatus.class);

    private final ProductCatalogCommandService service = new ProductCatalogCommandService(
            clock, eventPublisher, brandMapper, categoryMapper, spuMapper, skuMapper, platformSkuPricingMapper,
            buyerMapper, buyerListingMapper,
            availabilityMapper, generator,
            mock(com.aifuli.module.supplychain.infrastructure.identity.ScopedBusinessCodeService.class),
            brandAdminService, categoryCommandService,
            productTypeValidationService, productClassificationValidationService, sellabilityService, deletionValidator, targetSchemaRegistry,
            skuSchemaRegistry, transactionManager);

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PlatformSpuDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PlatformSkuDO.class);
        when(clock.now()).thenReturn(LocalDateTime.now());
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(spuMapper.update(isNull(), any())).thenReturn(1);
        when(skuMapper.selectByIdForUpdate(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return skuMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PlatformSkuDO>())
                    .stream().filter(row -> id.equals(row.getId())).findFirst().orElse(null);
        });
    }

    /** 同步模式不能因为父已上架而跳过草稿或主动下架的全部规格。 */
    @Test
    void synchronizedShelvingUpdatesAllMembersInOneTransaction() throws Exception {
        PlatformSpuDO spu = PlatformSpuDO.builder().id(1L).spuCode("SPU_SYNC")
                .status(CatalogEntityStatus.ACTIVE.getCode()).productType("EQUITY_PRODUCT").version(0L).build();
        when(spuMapper.selectByCode("SPU_SYNC")).thenReturn(spu);
        when(spuMapper.selectByCodeForUpdate("SPU_SYNC")).thenReturn(spu);
        when(spuMapper.updateById(any(PlatformSpuDO.class))).thenReturn(1);
        List<PlatformSkuDO> members = List.of(syncSku(10L, "SKU_DRAFT", CatalogEntityStatus.DRAFT),
                syncSku(11L, "SKU_OFF", CatalogEntityStatus.INACTIVE));
        when(skuMapper.selectList(any())).thenReturn(members);
        when(skuMapper.updateById(any(PlatformSkuDO.class))).thenReturn(1);

        BatchStatusResult result = service.batchChangeStatus(syncCommand(List.of("SPU_SYNC"), List.of(), CatalogEntityStatus.ACTIVE));

        assertThat(result.succeeded()).containsExactly("SPU_SYNC");
        verify(skuMapper, times(2)).updateById(any(PlatformSkuDO.class));
        assertThat(members).allSatisfy(sku -> assertThat(sku.getStatus()).isEqualTo(CatalogEntityStatus.ACTIVE.getCode()));
        verify(transactionManager).getTransaction(any());
        // 与复合编辑统一父→类型→SKU顺序，避免跨父定价批次连接形成三方环锁。
        var lockOrder = inOrder(productTypeValidationService, skuMapper);
        lockOrder.verify(productTypeValidationService).requireActiveForUpdate("EQUITY_PRODUCT");
        lockOrder.verify(skuMapper).selectByIdForUpdate(10L);
    }

    @Test
    void synchronizedShelvingRejectsExplicitSkuAndInactiveTarget() throws Exception {
        assertThatThrownBy(() -> service.batchChangeStatus(syncCommand(List.of("SPU_SYNC"), List.of("SKU_A"), CatalogEntityStatus.ACTIVE)))
                .hasFieldOrPropertyWithValue("code", PRODUCT_BATCH_INPUT_INVALID.getCode());
        assertThatThrownBy(() -> service.batchChangeStatus(syncCommand(List.of("SPU_SYNC"), List.of(), CatalogEntityStatus.INACTIVE)))
                .hasFieldOrPropertyWithValue("code", PRODUCT_BATCH_INPUT_INVALID.getCode());
        verify(transactionManager, never()).getTransaction(any());
    }

    @Test
    void synchronizedShelvingRejectsExpandedOverLimitBeforeOpeningAnyTransaction() throws Exception {
        when(spuMapper.selectByCode("SPU_SYNC")).thenReturn(PlatformSpuDO.builder().id(1L).spuCode("SPU_SYNC")
                .status(CatalogEntityStatus.DRAFT.getCode()).build());
        when(skuMapper.selectList(any())).thenReturn(IntStream.rangeClosed(1, 200)
                .mapToObj(i -> syncSku(i, "SKU_" + i, CatalogEntityStatus.DRAFT)).toList());
        assertThatThrownBy(() -> service.batchChangeStatus(syncCommand(List.of("SPU_SYNC"), List.of(), CatalogEntityStatus.ACTIVE)))
                .hasFieldOrPropertyWithValue("code", PRODUCT_BATCH_INPUT_INVALID.getCode());
        verify(transactionManager, never()).getTransaction(any());
        verify(spuMapper, never()).updateById(any(PlatformSpuDO.class));
    }

    private PlatformSkuDO syncSku(long id, String code, CatalogEntityStatus status) {
        return PlatformSkuDO.builder().id(id).spuId(1L).skuCode(code).skuName(code).status(status.getCode())
                .targetSchemaCode("DIRECT_RECHARGE_TARGET").targetSchemaVersion(1)
                .resultSchemaCode("DIRECT_RECHARGE_RESULT").resultSchemaVersion(1).version(0L).build();
    }

    private BatchStatusCommand syncCommand(List<String> spus, List<String> skus, CatalogEntityStatus target) {
        return new BatchStatusCommand(spus, skus, target.getCode(), null, 99L, true);
    }

    @Test
    void synchronizedShelvingDeduplicatesBeforeApplyingExpandedLimit() {
        PlatformSpuDO spu = PlatformSpuDO.builder().id(1L).spuCode("SPU_SYNC")
                .status(CatalogEntityStatus.ACTIVE.getCode()).productType("EQUITY_PRODUCT").version(0L).build();
        when(spuMapper.selectByCode("SPU_SYNC")).thenReturn(spu);
        when(spuMapper.selectByCodeForUpdate("SPU_SYNC")).thenReturn(spu);
        when(spuMapper.updateById(any(PlatformSpuDO.class))).thenReturn(1);
        when(skuMapper.selectList(any())).thenReturn(List.of(syncSku(10L, "SKU_DRAFT", CatalogEntityStatus.DRAFT)));
        when(skuMapper.updateById(any(PlatformSkuDO.class))).thenReturn(1);
        java.util.concurrent.atomic.AtomicReference<BatchStatusResult> captured = new java.util.concurrent.atomic.AtomicReference<>();
        org.assertj.core.api.Assertions.assertThatCode(() -> captured.set(service.batchChangeStatus(
                syncCommand(java.util.Collections.nCopies(201, "SPU_SYNC"), List.of(), CatalogEntityStatus.ACTIVE))))
                .doesNotThrowAnyException();
        BatchStatusResult result = captured.get();
        assertThat(result.succeeded()).containsExactly("SPU_SYNC");
        assertThat(result.groups()).singleElement().satisfies(group -> assertThat(group.skuCodes()).containsExactly("SKU_DRAFT"));
        verify(transactionManager).commit(transactionStatus);
    }

    @Test
    void synchronizedShelvingRejectsEmptyAndSkuOnlySelection() {
        assertThatThrownBy(() -> service.batchChangeStatus(syncCommand(List.of(), List.of(), CatalogEntityStatus.ACTIVE)))
                .hasFieldOrPropertyWithValue("code", PRODUCT_BATCH_INPUT_INVALID.getCode());
        assertThatThrownBy(() -> service.batchChangeStatus(syncCommand(List.of(), List.of("SKU_X"), CatalogEntityStatus.ACTIVE)))
                .hasFieldOrPropertyWithValue("code", PRODUCT_BATCH_INPUT_INVALID.getCode());
        verify(transactionManager, never()).getTransaction(any());
    }

    @Test
    void synchronizedShelvingRecordsMissingArchivedAndNoSkuGroupsAsParentFailure() {
        PlatformSpuDO archived = PlatformSpuDO.builder().id(2L).spuCode("SPU_ARCHIVED").status(CatalogEntityStatus.ARCHIVED.getCode()).build();
        PlatformSpuDO empty = PlatformSpuDO.builder().id(3L).spuCode("SPU_EMPTY").status(CatalogEntityStatus.DRAFT.getCode()).build();
        when(spuMapper.selectByCode("SPU_ARCHIVED")).thenReturn(archived);
        when(spuMapper.selectByCodeForUpdate("SPU_ARCHIVED")).thenReturn(archived);
        when(spuMapper.selectByCode("SPU_EMPTY")).thenReturn(empty);
        when(spuMapper.selectByCodeForUpdate("SPU_EMPTY")).thenReturn(empty);
        when(skuMapper.selectList(any())).thenReturn(List.of());
        BatchStatusResult result = service.batchChangeStatus(syncCommand(List.of("SPU_ABSENT", "SPU_ARCHIVED", "SPU_EMPTY"), List.of(), CatalogEntityStatus.ACTIVE));
        assertThat(result.succeeded()).isEmpty();
        assertThat(result.failed()).extracting(BatchStatusFailedItem::errorCode).containsExactly(
                PLATFORM_SPU_NOT_FOUND.getCode(), PLATFORM_SPU_NOT_FOUND.getCode(),
                com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_SKU_REQUIRED.getCode());
        assertThat(result.groups()).hasSize(3).allSatisfy(group -> {
            assertThat(group.skuCodes()).isEmpty();
            assertThat(group.succeeded()).isFalse();
            assertThat(group.failedEntityType()).isEqualTo(CatalogEntityType.SPU);
            assertThat(group.failedEntityCode()).isEqualTo(group.spuCode());
        });
        verify(spuMapper, never()).updateById(any(PlatformSpuDO.class));
    }

    @Test
    void synchronizedShelvingDetectsMemberChangesBeforeWritingAnyEntity() {
        PlatformSpuDO spu = PlatformSpuDO.builder().id(1L).spuCode("SPU_SYNC").status(CatalogEntityStatus.DRAFT.getCode()).build();
        when(spuMapper.selectByCode("SPU_SYNC")).thenReturn(spu);
        when(spuMapper.selectByCodeForUpdate("SPU_SYNC")).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of(syncSku(10L, "SKU_ONE", CatalogEntityStatus.DRAFT)),
                List.of(syncSku(10L, "SKU_ONE", CatalogEntityStatus.DRAFT), syncSku(11L, "SKU_NEW", CatalogEntityStatus.DRAFT)));
        BatchStatusResult result = service.batchChangeStatus(syncCommand(List.of("SPU_SYNC"), List.of(), CatalogEntityStatus.ACTIVE));
        assertThat(result.failed()).singleElement().satisfies(item -> {
            assertThat(item.errorCode()).isEqualTo(com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_CONCURRENT_UPDATE.getCode());
            assertThat(item.failedEntityType()).isEqualTo(CatalogEntityType.SPU);
        });
        assertThat(result.groups().getFirst().skuCodes()).containsExactly("SKU_ONE");
        verify(spuMapper, never()).updateById(any(PlatformSpuDO.class));
    }

    @Test
    void synchronizedShelvingLocatesInvalidSchemaSkuAndValidatesAllBeforeWriting() {
        PlatformSpuDO spu = PlatformSpuDO.builder().id(1L).spuCode("SPU_SYNC").status(CatalogEntityStatus.DRAFT.getCode()).build();
        PlatformSkuDO bad = syncSku(11L, "SKU_BAD", CatalogEntityStatus.DRAFT);
        bad.setResultSchemaCode("DEFAULT");
        when(spuMapper.selectByCode("SPU_SYNC")).thenReturn(spu);
        when(spuMapper.selectByCodeForUpdate("SPU_SYNC")).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of(syncSku(10L, "SKU_GOOD", CatalogEntityStatus.DRAFT), bad));
        BatchStatusResult result = service.batchChangeStatus(syncCommand(List.of("SPU_SYNC"), List.of(), CatalogEntityStatus.ACTIVE));
        assertThat(result.failed()).singleElement().satisfies(item -> {
            assertThat(item.code()).isEqualTo("SPU_SYNC");
            assertThat(item.entityType()).isEqualTo(CatalogEntityType.SPU);
            assertThat(item.failedEntityType()).isEqualTo(CatalogEntityType.SKU);
            assertThat(item.failedEntityCode()).isEqualTo("SKU_BAD");
            assertThat(item.errorCode()).isEqualTo(com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_SCHEMA_INVALID.getCode());
        });
        assertThat(result.groups().getFirst().skuCodes()).containsExactly("SKU_GOOD", "SKU_BAD");
        verify(spuMapper, never()).updateById(any(PlatformSpuDO.class));
        verify(skuMapper, never()).updateById(any(PlatformSkuDO.class));
        verify(transactionManager).rollback(transactionStatus);
    }

    private void stubActiveSpu(long id, String spuCode) {
        when(spuMapper.selectByCodeForUpdate(spuCode)).thenReturn(PlatformSpuDO.builder()
                .id(id)
                .spuCode(spuCode)
                .status(CatalogEntityStatus.ACTIVE.getCode())
                .version(0L)
                .build());
        when(spuMapper.updateById(any(PlatformSpuDO.class))).thenReturn(1);
    }

    private void stubActiveSku(long id, String skuCode) {
        when(skuMapper.selectByCodeForUpdate(skuCode)).thenReturn(PlatformSkuDO.builder()
                .id(id)
                .skuCode(skuCode)
                .status(CatalogEntityStatus.ACTIVE.getCode())
                .version(0L)
                .build());
        when(skuMapper.updateById(any(PlatformSkuDO.class))).thenReturn(1);
    }

    @Test
    void batchUnshelveSetsStatusAndManualMode() {
        stubActiveSpu(1L, "SPU_001");
        stubActiveSku(10L, "SKU_001");

        BatchStatusCommand cmd = new BatchStatusCommand(
                List.of("SPU_001"), List.of("SKU_001"), CatalogEntityStatus.INACTIVE.getCode(), null, 99L);

        BatchStatusResult result = service.batchChangeStatus(cmd);

        ArgumentCaptor<PlatformSpuDO> spuCaptor = ArgumentCaptor.forClass(PlatformSpuDO.class);
        verify(spuMapper).updateById(spuCaptor.capture());
        assertThat(spuCaptor.getValue().getStatus()).isEqualTo(CatalogEntityStatus.INACTIVE.getCode());
        assertThat(spuCaptor.getValue().getUnshelveMode()).isEqualTo(UnshelveMode.MANUAL_UNSHELVE.getCode());

        ArgumentCaptor<PlatformSkuDO> skuCaptor = ArgumentCaptor.forClass(PlatformSkuDO.class);
        verify(skuMapper).updateById(skuCaptor.capture());
        assertThat(skuCaptor.getValue().getStatus()).isEqualTo(CatalogEntityStatus.INACTIVE.getCode());

        assertThat(result.succeeded()).containsExactly("SPU_001", "SKU_001");
        assertThat(result.failed()).isEmpty();
    }

    @Test
    void batchShelveClearsUnshelveMode() {
        PlatformSpuDO spu = PlatformSpuDO.builder()
                .id(2L)
                .spuCode("SPU_002")
                .productType("PT_CARD")
                .status(CatalogEntityStatus.INACTIVE.getCode())
                .unshelveMode(UnshelveMode.MANUAL_UNSHELVE.getCode())
                .version(0L)
                .build();
        when(spuMapper.selectByCodeForUpdate("SPU_002")).thenReturn(spu);
        when(spuMapper.updateById(any(PlatformSpuDO.class))).thenReturn(1);

        BatchStatusCommand cmd = new BatchStatusCommand(
                List.of("SPU_002"), null, CatalogEntityStatus.ACTIVE.getCode(), null, 99L);

        BatchStatusResult result = service.batchChangeStatus(cmd);

        ArgumentCaptor<PlatformSpuDO> spuCaptor = ArgumentCaptor.forClass(PlatformSpuDO.class);
        verify(spuMapper).updateById(spuCaptor.capture());
        assertThat(spuCaptor.getValue().getStatus()).isEqualTo(CatalogEntityStatus.ACTIVE.getCode());
        assertThat(spuCaptor.getValue().getUnshelveMode()).isNull();

        assertThat(result.succeeded()).containsExactly("SPU_002");
    }

    /** D1：传播行为与隔离级别必须由代码固定，不能依赖「调用链恰好无外层事务」。 */
    @Test
    void batchConfiguresRequiresNewReadCommittedPerItem() {
        stubActiveSpu(1L, "SPU_001");

        service.batchChangeStatus(new BatchStatusCommand(
                List.of("SPU_001"), null, CatalogEntityStatus.INACTIVE.getCode(), null, 99L));

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(definition.getValue().getIsolationLevel())
                .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
        verify(transactionManager).commit(transactionStatus);
        verify(transactionManager, never()).rollback(any());
    }

    /** D1：每项各自开启并提交一个事务，SPU 与 SKU 不共用事务边界。 */
    @Test
    void batchOpensOneTransactionPerItem() {
        stubActiveSpu(1L, "SPU_001");
        stubActiveSku(10L, "SKU_001");

        BatchStatusResult result = service.batchChangeStatus(new BatchStatusCommand(
                List.of("SPU_001"), List.of("SKU_001"), CatalogEntityStatus.INACTIVE.getCode(), null, 99L));

        assertThat(result.succeeded()).containsExactly("SPU_001", "SKU_001");
        assertThat(result.failed()).isEmpty();
        verify(transactionManager, times(2)).getTransaction(any());
        verify(transactionManager, times(2)).commit(transactionStatus);
    }

    @Test
    void batchDisallowsInvalidTargetStatus() {
        BatchStatusCommand cmd = new BatchStatusCommand(
                List.of("SPU_003"), null, CatalogEntityStatus.ARCHIVED.getCode(), null, 99L);

        assertThatThrownBy(() -> service.batchChangeStatus(cmd))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PRODUCT_STATUS_INVALID.getCode());

        verify(transactionManager, never()).getTransaction(any());
    }

    /** D3：未选择任何商品整体拒绝，且不开启任何事务。 */
    @Test
    void batchRejectsEmptyInput() {
        BatchStatusCommand cmd = new BatchStatusCommand(
                null, null, CatalogEntityStatus.INACTIVE.getCode(), null, 99L);

        assertThatThrownBy(() -> service.batchChangeStatus(cmd))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PRODUCT_BATCH_INPUT_INVALID.getCode());

        verify(transactionManager, never()).getTransaction(any());
    }

    /** D3：空白编码整体拒绝而非静默跳过，否则 succeeded+failed 会少于入参条数、前端计数失真。 */
    @Test
    void batchRejectsBlankCode() {
        BatchStatusCommand cmd = new BatchStatusCommand(
                List.of("  ", "SPU_001"), null, CatalogEntityStatus.INACTIVE.getCode(), null, 99L);

        assertThatThrownBy(() -> service.batchChangeStatus(cmd))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PRODUCT_BATCH_INPUT_INVALID.getCode());

        verify(transactionManager, never()).getTransaction(any());
        verify(spuMapper, never()).updateById(any(PlatformSpuDO.class));
    }

    /** D3：SPU 与 SKU 项数之和超过 200 整体拒绝——真正决定独立事务数量的是总项数。 */
    @Test
    void batchRejectsOverLimit() {
        List<String> spuCodes = IntStream.rangeClosed(1, 150).mapToObj(i -> "SPU_" + i).toList();
        List<String> skuCodes = IntStream.rangeClosed(1, 51).mapToObj(i -> "SKU_" + i).toList();

        assertThatThrownBy(() -> service.batchChangeStatus(new BatchStatusCommand(
                spuCodes, skuCodes, CatalogEntityStatus.INACTIVE.getCode(), null, 99L)))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PRODUCT_BATCH_INPUT_INVALID.getCode());

        verify(transactionManager, never()).getTransaction(any());
    }

    /** D3 边界：恰好 200 项放行，同时验证 succeeded+failed 恒等于入参条数。 */
    @Test
    void batchAcceptsExactlyMaxItems() {
        when(spuMapper.selectByCodeForUpdate(anyString())).thenReturn(null);
        List<String> spuCodes = IntStream.rangeClosed(1, 200).mapToObj(i -> "SPU_" + i).toList();

        BatchStatusResult result = service.batchChangeStatus(new BatchStatusCommand(
                spuCodes, null, CatalogEntityStatus.INACTIVE.getCode(), null, 99L));

        assertThat(result.succeeded()).isEmpty();
        assertThat(result.failed()).hasSize(200);
        assertThat(result.succeeded().size() + result.failed().size()).isEqualTo(spuCodes.size());
    }

    /** D1：失败项仅回滚自己，成功项照常提交。 */
    @Test
    void batchRecordsPartialFailureDetail() {
        stubActiveSpu(1L, "SPU_001");
        when(spuMapper.selectByCodeForUpdate("SPU_999")).thenReturn(null);

        BatchStatusCommand cmd = new BatchStatusCommand(
                List.of("SPU_001", "SPU_999"), null,
                CatalogEntityStatus.INACTIVE.getCode(), null, 99L);

        BatchStatusResult result = service.batchChangeStatus(cmd);

        assertThat(result.succeeded()).containsExactly("SPU_001");
        assertThat(result.failed()).hasSize(1);
        BatchStatusFailedItem failedItem = result.failed().get(0);
        assertThat(failedItem.entityType()).isEqualTo(CatalogEntityType.SPU);
        assertThat(failedItem.code()).isEqualTo("SPU_999");
        assertThat(failedItem.errorCode()).isEqualTo(PLATFORM_SPU_NOT_FOUND.getCode());
        assertThat(failedItem.errorMessage()).isEqualTo(PLATFORM_SPU_NOT_FOUND.getMsg());
        verify(transactionManager, times(1)).commit(transactionStatus);
        verify(transactionManager, times(1)).rollback(transactionStatus);
    }

    /** SKU 失败分支同样带实体类型、稳定错误码与消息。 */
    @Test
    void batchRecordsSkuFailureDetail() {
        when(skuMapper.selectByCodeForUpdate("SKU_999")).thenReturn(null);

        BatchStatusResult result = service.batchChangeStatus(new BatchStatusCommand(
                null, List.of("SKU_999"), CatalogEntityStatus.INACTIVE.getCode(), null, 99L));

        assertThat(result.succeeded()).isEmpty();
        assertThat(result.failed()).hasSize(1);
        BatchStatusFailedItem failedItem = result.failed().get(0);
        assertThat(failedItem.entityType()).isEqualTo(CatalogEntityType.SKU);
        assertThat(failedItem.code()).isEqualTo("SKU_999");
        assertThat(failedItem.errorCode()).isEqualTo(PLATFORM_SKU_NOT_FOUND.getCode());
        assertThat(failedItem.errorMessage()).isEqualTo(PLATFORM_SKU_NOT_FOUND.getMsg());
    }

    @Test
    void batchHandlesMixedSpuAndSku() {
        stubActiveSpu(1L, "SPU_001");
        stubActiveSku(10L, "SKU_001");

        BatchStatusCommand cmd = new BatchStatusCommand(
                List.of("SPU_001"), List.of("SKU_001"),
                CatalogEntityStatus.INACTIVE.getCode(), null, 99L);

        BatchStatusResult result = service.batchChangeStatus(cmd);

        assertThat(result.succeeded()).containsExactly("SPU_001", "SKU_001");
        assertThat(result.failed()).isEmpty();
        verify(transactionManager, times(2)).getTransaction(any());
    }

    @Test
    void batchAllFailedReturnsAllItemsInFailedList() {
        when(spuMapper.selectByCodeForUpdate("SPU_X1")).thenReturn(null);
        when(spuMapper.selectByCodeForUpdate("SPU_X2")).thenReturn(null);

        BatchStatusCommand cmd = new BatchStatusCommand(
                List.of("SPU_X1", "SPU_X2"), null,
                CatalogEntityStatus.INACTIVE.getCode(), null, 99L);

        BatchStatusResult result = service.batchChangeStatus(cmd);

        assertThat(result.succeeded()).isEmpty();
        assertThat(result.failed()).hasSize(2);
        assertThat(result.failed()).allSatisfy(item -> {
            assertThat(item.entityType()).isEqualTo(CatalogEntityType.SPU);
            assertThat(item.errorCode()).isEqualTo(PLATFORM_SPU_NOT_FOUND.getCode());
        });
    }

    /**
     * D2：存储类系统异常不得伪装成业务拒绝。异常原样上抛交全局异常处理，
     * 且不进入 failed 明细；此前已提交项因 {@code REQUIRES_NEW} 不受影响。
     */
    @Test
    void batchPropagatesSystemExceptionWithoutMasking() {
        stubActiveSpu(1L, "SPU_001");
        when(spuMapper.selectByCodeForUpdate("SPU_BOOM"))
                .thenThrow(new IllegalStateException("连接池耗尽"));

        BatchStatusCommand cmd = new BatchStatusCommand(
                List.of("SPU_001", "SPU_BOOM"), null,
                CatalogEntityStatus.INACTIVE.getCode(), null, 99L);

        assertThatThrownBy(() -> service.batchChangeStatus(cmd))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("连接池耗尽");

        // 已成功项已提交，失败的系统异常项只回滚自己，不影响前一项。
        verify(transactionManager, times(1)).commit(transactionStatus);
        verify(transactionManager, times(1)).rollback(transactionStatus);
    }

    /** 方案 A：SPU 与 SKU 当前已是目标状态（如 INACTIVE）时，同状态变更幂等放行，计入 succeeded。 */
    @Test
    void batchIdempotentWhenAlreadyInactiveStatus() {
        stubSpuWithStatus(1L, "SPU_001", CatalogEntityStatus.INACTIVE);
        stubSkuWithStatus(10L, "SKU_001", CatalogEntityStatus.INACTIVE);

        BatchStatusResult result = service.batchChangeStatus(new BatchStatusCommand(
                List.of("SPU_001"), List.of("SKU_001"), CatalogEntityStatus.INACTIVE.getCode(), null, 99L));

        assertThat(result.succeeded()).containsExactly("SPU_001", "SKU_001");
        assertThat(result.failed()).isEmpty();
        verify(spuMapper).updateById(any(PlatformSpuDO.class));
        verify(skuMapper).updateById(any(PlatformSkuDO.class));
    }

    /** 方案 A：当前已是 ACTIVE 时再次上架，幂等成功放行。 */
    @Test
    void batchIdempotentWhenAlreadyActiveStatus() {
        stubSpuWithStatus(2L, "SPU_002", CatalogEntityStatus.ACTIVE);
        stubSkuWithStatus(20L, "SKU_002", CatalogEntityStatus.ACTIVE);

        BatchStatusResult result = service.batchChangeStatus(new BatchStatusCommand(
                List.of("SPU_002"), List.of("SKU_002"), CatalogEntityStatus.ACTIVE.getCode(), null, 99L));

        assertThat(result.succeeded()).containsExactly("SPU_002", "SKU_002");
        assertThat(result.failed()).isEmpty();
        verify(productTypeValidationService).requireActiveForUpdate(any());
        verify(spuMapper).updateById(any(PlatformSpuDO.class));
        verify(skuMapper).updateById(any(PlatformSkuDO.class));
    }

    /** 方案 A：草稿状态 SPU 不支持直接下架，拒绝并返回 PRODUCT_STATUS_TRANSITION_NOT_ALLOWED 且附带中文提示。 */
    @Test
    void batchTransitionNotAllowedForDraftToInactive() {
        stubSpuWithStatus(3L, "SPU_DRAFT", CatalogEntityStatus.DRAFT);

        BatchStatusResult result = service.batchChangeStatus(new BatchStatusCommand(
                List.of("SPU_DRAFT"), null, CatalogEntityStatus.INACTIVE.getCode(), null, 99L));

        assertThat(result.succeeded()).isEmpty();
        assertThat(result.failed()).hasSize(1);
        BatchStatusFailedItem item = result.failed().get(0);
        assertThat(item.code()).isEqualTo("SPU_DRAFT");
        assertThat(item.errorCode()).isEqualTo(PRODUCT_STATUS_TRANSITION_NOT_ALLOWED.getCode());
        assertThat(item.errorMessage())
                .contains("测试商品_SPU_DRAFT")
                .contains("SPU_DRAFT")
                .contains("草稿")
                .contains("停用");
    }

    /** 方案 A：草稿状态 SKU 不支持直接下架，拒绝并返回包含 SKU 名称与编码的错误提示。 */
    @Test
    void batchTransitionNotAllowedForDraftSkuToInactive() {
        stubSkuWithStatus(30L, "SKU_DRAFT", CatalogEntityStatus.DRAFT);

        BatchStatusResult result = service.batchChangeStatus(new BatchStatusCommand(
                null, List.of("SKU_DRAFT"), CatalogEntityStatus.INACTIVE.getCode(), null, 99L));

        assertThat(result.succeeded()).isEmpty();
        assertThat(result.failed()).hasSize(1);
        BatchStatusFailedItem item = result.failed().get(0);
        assertThat(item.code()).isEqualTo("SKU_DRAFT");
        assertThat(item.errorCode()).isEqualTo(PRODUCT_STATUS_TRANSITION_NOT_ALLOWED.getCode());
        assertThat(item.errorMessage())
                .contains("测试规格_SKU_DRAFT")
                .contains("SKU_DRAFT")
                .contains("草稿")
                .contains("停用");
    }

    /** 方案 A：已归档状态 SPU 终态不可流转，拒绝并返回清晰提示。 */
    @Test
    void batchTransitionNotAllowedForArchived() {
        // 注：selectByCodeForUpdate 查出已在库但状态为 ARCHIVED 的实体
        when(spuMapper.selectByCodeForUpdate("SPU_ARCHIVED")).thenReturn(PlatformSpuDO.builder()
                .id(4L)
                .spuCode("SPU_ARCHIVED")
                .spuName("已归档商品")
                .status(CatalogEntityStatus.ARCHIVED.getCode())
                .version(0L)
                .build());

        BatchStatusResult result = service.batchChangeStatus(new BatchStatusCommand(
                List.of("SPU_ARCHIVED"), null, CatalogEntityStatus.INACTIVE.getCode(), null, 99L));

        assertThat(result.succeeded()).isEmpty();
        assertThat(result.failed()).hasSize(1);
        BatchStatusFailedItem item = result.failed().get(0);
        assertThat(item.code()).isEqualTo("SPU_ARCHIVED");
        // 查实体时若已归档会按 PLATFORM_SPU_NOT_FOUND 处理（requireSpuForUpdate 拦截已归档），符合领域安全边界
        assertThat(item.errorCode()).isIn(
                PRODUCT_STATUS_TRANSITION_NOT_ALLOWED.getCode(),
                PLATFORM_SPU_NOT_FOUND.getCode());
    }

    private void stubSpuWithStatus(long id, String spuCode, CatalogEntityStatus status) {
        when(spuMapper.selectByCodeForUpdate(spuCode)).thenReturn(PlatformSpuDO.builder()
                .id(id)
                .spuCode(spuCode)
                .spuName("测试商品_" + spuCode)
                .status(status.getCode())
                .productType("EQUITY_PRODUCT")
                .version(0L)
                .build());
        when(spuMapper.updateById(any(PlatformSpuDO.class))).thenReturn(1);
    }

    private void stubSkuWithStatus(long id, String skuCode, CatalogEntityStatus status) {
        when(skuMapper.selectByCodeForUpdate(skuCode)).thenReturn(PlatformSkuDO.builder()
                .id(id)
                .skuCode(skuCode)
                .skuName("测试规格_" + skuCode)
                .status(status.getCode())
                .targetSchemaCode("DIRECT_RECHARGE_TARGET")
                .targetSchemaVersion(1)
                .resultSchemaCode(com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentResultSchemaCode.DIRECT_RECHARGE_RESULT.name())
                .resultSchemaVersion(1)
                .version(0L)
                .build());
        when(skuMapper.updateById(any(PlatformSkuDO.class))).thenReturn(1);
    }
}
