package com.aifuli.module.supplychain.api.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Schema(description = "采购商商品增量变更批次")
@Data
public class BuyerCatalogChangeBatchRespDTO implements Serializable {

    @Schema(description = "本批变更", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<BuyerCatalogChangeRespDTO> changes;

    @Schema(description = "本批处理完成后应持久化的游标", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "128")
    private Long nextCursor;

    @Schema(description = "是否仍有后续变更", requiredMode = Schema.RequiredMode.REQUIRED, example = "false")
    private Boolean hasMore;
}
