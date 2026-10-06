package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingStatus;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 自动目录在B前必须失败关闭；见桥接设计§4.4。 */
class BuyerListingManagementSafetyTest {
    private final BuyerListingMapper listings = mock(BuyerListingMapper.class);
    private final BuyerListingVersionMapper versions = mock(BuyerListingVersionMapper.class);
    private final BuyerListingAvailabilityMapper availability = mock(BuyerListingAvailabilityMapper.class);
    private final BuyerCatalogChangeMapper changes = mock(BuyerCatalogChangeMapper.class);
    private final BuyerMapper buyers = mock(BuyerMapper.class);
    private final ProductSellabilityService sellability = mock(ProductSellabilityService.class);
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final SupplyChainDatabaseClock clock = mock(SupplyChainDatabaseClock.class);
    private final BuyerListingPublicationService service = new BuyerListingPublicationService(
            clock, listings, versions, availability, changes, buyers, sellability, outbox);
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 4, 12, 0);

    @Test
    void automaticDraftRejectsExternalPriceBeforeAnyCommercialWrite() {
        when(listings.selectByListingNoForUpdate("LST_A")).thenReturn(auto(BuyerListingStatus.DRAFT));
        when(buyers.selectById(1L)).thenReturn(BuyerDO.builder().id(1L).status((short) 1).build());
        when(clock.now()).thenReturn(now);
        when(listings.update(isNull(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1);
        assertThatThrownBy(() -> service.publish(new BuyerListingPublicationService.PublishListingCommand(
                "LST_A", 0L, 999L, "外部价", now, null, "发布", 2L, "trace", null, null)))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode()).isEqualTo(1_050_001_172);
        verifyNoInteractions(versions, availability, changes, outbox);
        verify(listings, never()).update(isNull(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }

    @Test
    void automaticSuspendedCannotResumeEvenWithCurrentVersion() {
        BuyerListingDO row = auto(BuyerListingStatus.SUSPENDED);
        row.setCurrentVersionId(10L);
        when(listings.selectByListingNoForUpdate("LST_A")).thenReturn(row);
        assertThatThrownBy(() -> service.changeStatus(new BuyerListingPublicationService.ChangeListingStatusCommand(
                "LST_A", 0L, BuyerListingStatus.PUBLISHED, 2L, "trace", null, null)))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode()).isEqualTo(1_050_001_173);
        verifyNoInteractions(versions, availability, changes, outbox);
        verify(listings, never()).update(isNull(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }

    private BuyerListingDO auto(BuyerListingStatus status) {
        return BuyerListingDO.builder().id(5L).listingNo("LST_A").buyerId(1L).platformSkuId(3L)
                .buyerSkuCode("BPD_7").managementMode("AUTO_SELECTION").buyerProductId(7L)
                .status(status.getCode()).version(0L).build();
    }
}
