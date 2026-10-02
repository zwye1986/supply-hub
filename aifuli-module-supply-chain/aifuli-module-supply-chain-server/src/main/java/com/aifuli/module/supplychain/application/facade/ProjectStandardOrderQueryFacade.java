package com.aifuli.module.supplychain.application.facade;

import com.aifuli.module.supplychain.api.auth.ProjectAuthOperations;
import com.aifuli.module.supplychain.api.fulfillment.dto.*;
import com.aifuli.module.supplychain.border.fulfillment.application.ProjectStandardAssetQueryService;
import com.aifuli.module.supplychain.border.fulfillment.application.ProjectStandardOrderQueryService;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor.StandardProductContext;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

/**
 * 标准查询入口仅校验可信操作与订单号后委派，不持有DAL或拼造旧用户身份。 设计见
 * docs/superpowers/specs/2026-10-02-project-standard-query-asset-design.md#1-分层与可信输入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectStandardOrderQueryFacade {
    private final ProjectStandardOrderQueryService orders;
    private final ProjectStandardAssetQueryService assets;

    public ProjectStandardOrderQueryRespDTO query(
            StandardProductContext context, String buyerOrderNo) {
        validate(context, buyerOrderNo, ProjectAuthOperations.STANDARD_ORDER_QUERY);
        log.debug("[项目标准查单入口] projectId={}, buyerOrderNo={}", context.projectId(), buyerOrderNo);
        return orders.query(context, buyerOrderNo);
    }

    public ProjectStandardOrderAssetsRespDTO assets(
            StandardProductContext context, String buyerOrderNo) {
        validate(context, buyerOrderNo, ProjectAuthOperations.STANDARD_ORDER_ASSET_QUERY);
        log.debug("[项目标准资产入口] projectId={}, buyerOrderNo={}", context.projectId(), buyerOrderNo);
        return assets.query(context, buyerOrderNo);
    }

    private static void validate(
            StandardProductContext context, String buyerOrderNo, String operation) {
        if (context == null || !operation.equals(context.operationCode()))
            throw new IllegalArgumentException("项目标准操作上下文不匹配");
        if (buyerOrderNo == null
                || buyerOrderNo.isBlank()
                || buyerOrderNo.length() > 128
                || buyerOrderNo.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("采购商订单号格式不正确");
    }
}
