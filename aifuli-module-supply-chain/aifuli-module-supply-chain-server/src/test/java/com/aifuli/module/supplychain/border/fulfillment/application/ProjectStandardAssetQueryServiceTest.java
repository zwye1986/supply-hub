package com.aifuli.module.supplychain.border.fulfillment.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aifuli.module.supplychain.api.auth.ProjectAuthOperations;
import com.aifuli.module.supplychain.api.fulfillment.dto.*;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.*;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor.StandardProductContext;

import org.junit.jupiter.api.Test;

import java.util.List;

class ProjectStandardAssetQueryServiceTest {
    @Test
    void pendingVoucherReturnsWaitAndAuditsBeforeReturn() {
        var queries = mock(ProjectStandardOrderQueryService.class);
        var audits = mock(ProjectStandardAssetAuditService.class);
        var decoder = mock(ProjectStandardAssetPayloadDecoder.class);
        var numbers = mock(BusinessNoGenerator.class);
        var ctx =
                new StandardProductContext(
                        3L, 2L, "app", 1, ProjectAuthOperations.STANDARD_ORDER_ASSET_QUERY);
        when(queries.snapshot(ctx, "ord"))
                .thenReturn(
                        new ProjectStandardOrderQueryService.Snapshot(
                                FulfillmentAcceptanceReceiptDO.builder()
                                        .targetSchemaCode("VOUCHER_DELIVERY_TARGET")
                                        .status((short) 0)
                                        .build(),
                                null,
                                null,
                                List.of(),
                                new ProjectStandardOrderQueryRespDTO()));
        when(numbers.next(any())).thenReturn("AUD_trace");
        var result =
                new ProjectStandardAssetQueryService(queries, decoder, audits, numbers)
                        .query(ctx, "ord");
        assertThat(result).isNotNull();
        assertThat(result.getAssets()).isEmpty();
        assertThat(result.getHint()).isEqualTo("请等待，稍后重新获卡");
        verify(audits).append(eq(ctx), isNull(), eq("AUD_trace"), anyList());
        verifyNoInteractions(decoder);
    }
}
