package com.aifuli.module.supplychain.tradecore.buyer.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort.EncryptedPayload;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.account.application.BuyerAccountOpeningService;
import com.aifuli.module.supplychain.tradecore.account.application.BuyerAccountAdjustmentService;
import com.aifuli.module.supplychain.tradecore.account.application.BuyerAccountOpeningService.OpeningBalanceCommand;
import com.aifuli.module.supplychain.tradecore.account.domain.Money;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerAdminCommandService.ContactLine;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerAdminCommandService.CreateBuyerCommand;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerAdminCommandService.UpdateBuyerCommand;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerTenantBindingDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerCategoryScopeMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerContactMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerCooperationModeMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerFileRefMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerIntegrationModeMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerTenantBindingMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerStatus;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductTypeValidationService;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductTypeDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_PROFILE_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_STATUS_CONFLICT;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ENUM_NOT_OPEN;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_CONTACT_REQUIRED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分销商管理端建档：BYR_ 编码、采购商账本期初、状态机与联系人校验。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#62-列表与档案}。
 */
class BuyerAdminCommandServiceTest {

    private final BuyerMapper buyerMapper = mock(BuyerMapper.class);
    private final BuyerContactMapper contactMapper = mock(BuyerContactMapper.class);
    private final BuyerIntegrationModeMapper integrationModeMapper = mock(BuyerIntegrationModeMapper.class);
    private final BuyerCooperationModeMapper cooperationModeMapper = mock(BuyerCooperationModeMapper.class);
    private final BuyerCategoryScopeMapper categoryScopeMapper = mock(BuyerCategoryScopeMapper.class);
    private final BuyerFileRefMapper fileRefMapper = mock(BuyerFileRefMapper.class);
    private final BuyerAccountOpeningService accountOpeningService = mock(BuyerAccountOpeningService.class);
    private final BuyerAccountAdjustmentService accountAdjustmentService = mock(BuyerAccountAdjustmentService.class);
    private final BusinessNoGenerator businessNoGenerator = mock(BusinessNoGenerator.class);
    private final com.aifuli.module.supplychain.infrastructure.identity.ScopedBusinessCodeService scopedBusinessCodeService =
            mock(com.aifuli.module.supplychain.infrastructure.identity.ScopedBusinessCodeService.class);
    private final EnvelopeCryptoPort envelopeCrypto = mock(EnvelopeCryptoPort.class);
    private final SupplyChainDatabaseClock databaseClock = mock(SupplyChainDatabaseClock.class);
    private final BuyerTenantBindingMapper tenantBindingMapper = mock(BuyerTenantBindingMapper.class);
    private final ProductTypeValidationService productTypeValidationService = mock(ProductTypeValidationService.class);
    private BuyerAdminCommandService service;

    @BeforeEach
    void setUp() {
        when(databaseClock.now()).thenReturn(LocalDateTime.of(2026, 9, 4, 11, 0));
        service = new BuyerAdminCommandService(
                databaseClock, buyerMapper, contactMapper, integrationModeMapper, cooperationModeMapper,
                categoryScopeMapper, fileRefMapper, accountOpeningService, accountAdjustmentService,
                businessNoGenerator, scopedBusinessCodeService,
                envelopeCrypto, productTypeValidationService);
        when(scopedBusinessCodeService.allocateRoot(
                org.mockito.ArgumentMatchers.eq(com.aifuli.module.supplychain.infrastructure.identity.ScopedBusinessCodeService.Kind.BYR),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.nullable(Long.class)))
                .thenReturn(new com.aifuli.module.supplychain.infrastructure.identity.ScopedBusinessCodeService.Allocation("BYR_001", 1));
        when(productTypeValidationService.requireActiveForUpdate(any())).thenAnswer(invocation ->
                ProductTypeDO.builder().typeCode(invocation.getArgument(0)).status((short) 10).build());
        when(envelopeCrypto.encrypt(any(), any())).thenReturn(new EncryptedPayload(new byte[]{1}, "test-v1"));
        when(buyerMapper.updateById(any(BuyerDO.class))).thenReturn(1);
    }

    @Test
    void createGeneratesByrCodeAndOpensBuyerAccountNotSupplier() throws Exception {
        when(buyerMapper.insert(any(BuyerDO.class))).thenAnswer(inv -> {
            BuyerDO row = inv.getArgument(0);
            row.setId(5L);
            return 1;
        });

        String code = service.create(command(0L));

        assertThat(code).isEqualTo("BYR_001");
        ArgumentCaptor<BuyerDO> inserted = ArgumentCaptor.forClass(BuyerDO.class);
        verify(buyerMapper).insert(inserted.capture());
        BuyerDO row = inserted.getValue();
        assertThat(row.getBuyerType()).isEqualTo("SAAS_MALL");
        assertThat(row.getShortName()).isEqualTo("测试分销商");
        assertThat(row.getDistributionLevel()).isEqualTo((short) 1);
        assertThat(row.getStatus()).isEqualTo(BuyerStatus.ACTIVE.getCode());
        assertThat(row.getParentBuyerId()).isNull();

        ArgumentCaptor<OpeningBalanceCommand> opening = ArgumentCaptor.forClass(OpeningBalanceCommand.class);
        verify(accountOpeningService).open(opening.capture());
        OpeningBalanceCommand open = opening.getValue();
        assertThat(open.buyerCode()).isEqualTo("BYR_001");
        assertThat(open.initialBalance()).isEqualTo(Money.cny(0L));
        assertThat(open.operatorId()).isEqualTo(1L);
        assertThat(open.evidenceRef()).isEqualTo("ADMIN_OPENING:5:CNY");
        assertThat(open.evidenceDigest()).isEqualTo(sha256("OPENING_BALANCE:5:CNY"));
        assertThat(open.auditNote()).isEqualTo("管理端分销商期初");
        assertThat(open.traceId()).isEqualTo("buyer-admin-test");
        assertThat(open.causationId()).isEqualTo("BYR_001");
        verify(tenantBindingMapper, never()).insert(any(BuyerTenantBindingDO.class));
        ArgumentCaptor<byte[]> aad = ArgumentCaptor.forClass(byte[].class);
        verify(envelopeCrypto, org.mockito.Mockito.atLeastOnce()).encrypt(any(), aad.capture());
        assertThat(aad.getAllValues().stream().map(bytes -> new String(bytes, StandardCharsets.UTF_8)))
                .contains("buyer:5:legalPersonMobile");
    }

    @Test
    void createTreatsNullOpeningAsZeroAndTruncatesShortName() {
        when(buyerMapper.insert(any(BuyerDO.class))).thenAnswer(inv -> {
            BuyerDO row = inv.getArgument(0);
            row.setId(8L);
            return 1;
        });
        String longName = "A".repeat(70);
        assertThat(longName.length()).isGreaterThan(64);

        service.create(command(null, longName));

        ArgumentCaptor<BuyerDO> inserted = ArgumentCaptor.forClass(BuyerDO.class);
        verify(buyerMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getShortName()).hasSize(64).isEqualTo(longName.substring(0, 64));
        ArgumentCaptor<OpeningBalanceCommand> opening = ArgumentCaptor.forClass(OpeningBalanceCommand.class);
        verify(accountOpeningService).open(opening.capture());
        assertThat(opening.getValue().initialBalance()).isEqualTo(Money.cny(0L));
    }

    @Test
    void createReplacesUnsetCooperationStartedAtWithNow() {
        when(buyerMapper.insert(any(BuyerDO.class))).thenAnswer(inv -> {
            BuyerDO row = inv.getArgument(0);
            row.setId(9L);
            return 1;
        });

        service.create(commandWithCooperation(LocalDateTime.of(1970, 1, 1, 8, 0)));

        ArgumentCaptor<BuyerDO> inserted = ArgumentCaptor.forClass(BuyerDO.class);
        verify(buyerMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getCooperationStartedAt())
                .isEqualTo(LocalDateTime.of(2026, 9, 4, 11, 0));
    }

    @Test
    void createKeepsExplicitCooperationStartedAt() {
        when(buyerMapper.insert(any(BuyerDO.class))).thenAnswer(inv -> {
            BuyerDO row = inv.getArgument(0);
            row.setId(10L);
            return 1;
        });
        LocalDateTime startedAt = LocalDateTime.of(2026, 9, 1, 9, 30);

        service.create(commandWithCooperation(startedAt));

        ArgumentCaptor<BuyerDO> inserted = ArgumentCaptor.forClass(BuyerDO.class);
        verify(buyerMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getCooperationStartedAt()).isEqualTo(startedAt);
    }

    @Test
    void updateIgnoresEpochCooperationStartedAt() {
        BuyerDO existing = buyer(BuyerStatus.ACTIVE);
        existing.setBuyerCode("BYR_EXISTING");
        existing.setCooperationStartedAt(LocalDateTime.of(2026, 8, 1, 9, 0));
        when(buyerMapper.selectByBuyerCodeForUpdate("BYR_EXISTING")).thenReturn(existing);

        service.update(updateWithCooperation(LocalDateTime.of(1970, 1, 1, 8, 0)));

        ArgumentCaptor<BuyerDO> updated = ArgumentCaptor.forClass(BuyerDO.class);
        verify(buyerMapper).updateById(updated.capture());
        assertThat(updated.getValue().getCooperationStartedAt())
                .isEqualTo(LocalDateTime.of(2026, 8, 1, 9, 0));
    }

    @Test
    void createRejectsUnknownSellableCategoryCode() {
        CreateBuyerCommand command = new CreateBuyerCommand(
                "测试分销商", List.of(new ContactLine("张三", "13800138000", "a@b.c")),
                null, null, null, null, null, 7, List.of(), List.of(),
                null, null, "测试科技有限公司", "91110000000000001X",
                "李四", "13900139000", "上海市浦东新区", "测试银行", "6222021234567890",
                null, null, null, null, List.of(), List.of(), List.of("NOT_A_CODE"), List.of(),
                0L, 1L, "buyer-admin-test");

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ENUM_NOT_OPEN.getCode());
    }

    @Test
    void updateAllowsTargetBalanceWithoutReopeningAccount() {
        when(buyerMapper.selectByBuyerCodeForUpdate("BYR_EXISTING")).thenReturn(buyer(BuyerStatus.ACTIVE));
        service.update(updateWithOpening(0L));
        verify(buyerMapper).updateById(any(BuyerDO.class));
        verify(accountOpeningService, never()).open(any());
        verify(accountAdjustmentService).adjust(new BuyerAccountAdjustmentService.AdjustmentCommand(
                5L, 0L, 1L, "buyer-admin-update-test"));
    }

    @Test
    void updateWithoutBalanceDoesNotAdjustAccount() {
        when(buyerMapper.selectByBuyerCodeForUpdate("BYR_EXISTING")).thenReturn(buyer(BuyerStatus.ACTIVE));
        service.update(updateWithOpening(null));
        verify(accountAdjustmentService, never()).adjust(any());
    }

    @Test
    void changeStatusUsesBuyerStatusMachine() {
        when(buyerMapper.selectByBuyerCodeForUpdate("BYR_ACTIVE")).thenReturn(buyer(BuyerStatus.ACTIVE));
        service.changeStatus("BYR_ACTIVE", BuyerStatus.BLACKLIST, 1L);
        ArgumentCaptor<BuyerDO> updated = ArgumentCaptor.forClass(BuyerDO.class);
        verify(buyerMapper).updateById(updated.capture());
        assertThat(updated.getValue().getStatus()).isEqualTo(BuyerStatus.BLACKLIST.getCode());

        when(buyerMapper.selectByBuyerCodeForUpdate("BYR_ARCHIVED")).thenReturn(buyer(BuyerStatus.ARCHIVED));
        assertThatThrownBy(() -> service.changeStatus("BYR_ARCHIVED", BuyerStatus.ACTIVE, 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(BUYER_STATUS_CONFLICT.getCode());

        when(buyerMapper.selectByBuyerCodeForUpdate("BYR_MISSING")).thenReturn(null);
        assertThatThrownBy(() -> service.changeStatus("BYR_MISSING", BuyerStatus.ACTIVE, 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(BUYER_PROFILE_NOT_FOUND.getCode());
    }

    @Test
    void rejectsEmptyContacts() {
        assertThatThrownBy(() -> BuyerAdminCommandService.requireContacts(List.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(SUPPLIER_CONTACT_REQUIRED.getCode());
    }

    private static CreateBuyerCommand command(Long opening) {
        return command(opening, "测试分销商");
    }

    private static CreateBuyerCommand command(Long opening, String name) {
        return new CreateBuyerCommand(
                name, List.of(new ContactLine("张三", "13800138000", "a@b.c")),
                null, null, null, null, null, 7, List.of(), List.of(),
                null, null, "测试科技有限公司", "91110000000000001X",
                "李四", "13900139000", "上海市浦东新区", "测试银行", "6222021234567890",
                null, null, null, null, List.of(), List.of(), List.of(), List.of(),
                opening, 1L, "buyer-admin-test");
    }

    private static CreateBuyerCommand commandWithCooperation(LocalDateTime cooperationStartedAt) {
        return new CreateBuyerCommand(
                "测试分销商", List.of(new ContactLine("张三", "13800138000", "a@b.c")),
                null, null, null, null, null, 7, List.of(), List.of(),
                cooperationStartedAt, null, "测试科技有限公司", "91110000000000001X",
                "李四", "13900139000", "上海市浦东新区", "测试银行", "6222021234567890",
                null, null, null, null, List.of(), List.of(), List.of(), List.of(),
                0L, 1L, "buyer-admin-test");
    }

    private static UpdateBuyerCommand updateWithOpening(Long opening) {
        return new UpdateBuyerCommand(
                "BYR_EXISTING", "测试分销商", List.of(new ContactLine("张三", "13800138000", "a@b.c")),
                null, 1, null, null, null, 7, List.of(), List.of(),
                null, null, "测试科技有限公司", "91110000000000001X",
                "李四", "13900139000", "上海市浦东新区", "测试银行", "6222021234567890",
                null, null, null, null, List.of(), List.of(), List.of(), List.of(),
                opening, 1L, "buyer-admin-update-test");
    }

    private static UpdateBuyerCommand updateWithCooperation(LocalDateTime cooperationStartedAt) {
        return new UpdateBuyerCommand(
                "BYR_EXISTING", "测试分销商", List.of(new ContactLine("张三", "13800138000", "a@b.c")),
                null, 1, null, null, null, 7, List.of(), List.of(),
                cooperationStartedAt, null, "测试科技有限公司", "91110000000000001X",
                "李四", "13900139000", "上海市浦东新区", "测试银行", "6222021234567890",
                null, null, null, null, List.of(), List.of(), List.of(), List.of(),
                null, 1L, "buyer-admin-update-test");
    }

    private static BuyerDO buyer(BuyerStatus status) {
        return BuyerDO.builder()
                .id(5L)
                .buyerCode("BYR_ACTIVE")
                .status(status.getCode())
                .distributionLevel((short) 1)
                .version(0L)
                .build();
    }

    private static byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    }
}
