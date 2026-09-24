package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.interfaceapi.admin.vo.RoutingRuleSaveReqVO;
import com.aifuli.module.supplychain.routing.application.RoutingRuleAdminCommandService;
import com.aifuli.module.supplychain.routing.application.RoutingRuleAdminQueryService;
import com.aifuli.module.supplychain.routing.application.RoutingRuleAdminQueryService.RoutingRulePageQuery;
import com.aifuli.module.supplychain.routing.application.RoutingRuleAdminQueryService.RoutingRuleView;
import com.aifuli.module.supplychain.routing.application.RoutingRuleRouteMode;
import com.aifuli.module.supplychain.routing.application.RoutingRuleSaveSupport;
import com.aifuli.module.supplychain.routing.application.RoutingRuleSaveSupport.NormalizedRulePatch;
import com.aifuli.module.supplychain.routing.application.RoutingRuleSaveSupport.PriorityInput;
import com.aifuli.module.supplychain.routing.application.RoutingRuleSaveSupport.SaveRequest;
import com.aifuli.module.supplychain.routing.application.RoutingRuleSaveSupport.WeightInput;
import com.aifuli.module.supplychain.routing.application.RoutingRuleScopeMode;
import com.aifuli.module.supplychain.routing.application.RoutingRuleStatus;
import com.aifuli.module.supplychain.routing.application.RoutingRuleStrategy;
import com.aifuli.module.supplychain.tradecore.profile.domain.ClosedEnums;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.aifuli.framework.common.pojo.CommonResult.success;
import static com.aifuli.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.ROUTING_RULE_CREATE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.ROUTING_RULE_DELETE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.ROUTING_RULE_QUERY_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.ROUTING_RULE_UPDATE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.ROUTING_RULE_UPDATE_STATUS_EXPRESSION;

/**
 * 管理端路由规则（PRD 四级范围模型）：GET 投影当前生效版本；写入口经补丁发布落库；
 * ENABLED/DISABLED 为规则真实状态；受保护 GLOBAL_DEFAULT 不可删除。
 * <p>设计见 {@code docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#56-rest-契约}。</p>
 */
@Slf4j
@Tag(name = "管理后台 - 供应链路由规则")
@RestController
@RequestMapping("/supply-chain/routing-rules")
@Validated
@RequiredArgsConstructor
public class RoutingRuleAdminController {

    private final RoutingRuleAdminCommandService commandService;
    private final RoutingRuleAdminQueryService queryService;
    /** SaveSupport 无 Spring 依赖，控制器内直接实例化。 */
    private final RoutingRuleSaveSupport saveSupport = new RoutingRuleSaveSupport();

    @GetMapping
    @PreAuthorize(ROUTING_RULE_QUERY_EXPRESSION)
    @Operation(summary = "分页查询当前生效的标准商品路由规则",
            description = "只读启用中 STANDARD_PRODUCT 策略的当前生效已发布版本；"
                    + "分销商/项目/商品类型/供应商四级范围与状态、更新人均真实过滤。"
                    + "设计见 docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md。")
    public CommonResult<PageResult<RoutingRuleView>> page(
            @Parameter(description = "规则中文名称，包含匹配")
            @Size(max = 128) @RequestParam(required = false) String name,
            @Parameter(description = "分销商业务编码；ALL 规则始终命中")
            @RequestParam(required = false) List<String> distributorCodes,
            @Parameter(description = "项目业务编码；ALL 规则始终命中")
            @RequestParam(required = false) List<String> projectCodes,
            @Parameter(description = "商品类型编码；ALL 规则始终命中")
            @RequestParam(required = false) List<String> productTypeCodes,
            @Parameter(description = "候选供应商业务编码，求交；ALL 规则始终命中")
            @RequestParam(required = false) List<String> supplierCodes,
            @Parameter(description = "启停状态 ENABLED/DISABLED")
            @RequestParam(required = false) String status,
            @Parameter(description = "更新人，匹配 updated_by 十进制字符串")
            @RequestParam(required = false) List<String> updaters,
            @Parameter(description = "规则更新时间下界，包含")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            @RequestParam(required = false) LocalDateTime updateFrom,
            @Parameter(description = "规则更新时间上界，包含")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            @RequestParam(required = false) LocalDateTime updateTo,
            @Parameter(description = "页码，从1开始")
            @Min(1) @RequestParam(required = false) Integer pageNo,
            @Parameter(description = "每页条数，最大100")
            @Min(1) @Max(100) @RequestParam(required = false) Integer pageSize) {
        log.info("路由规则分页入口 userId={} name={} distributorCodes={} projectCodes={} productTypeCodes={} "
                        + "supplierCodes={} status={} updaters={} updateFrom={} updateTo={} pageNo={} pageSize={}",
                getLoginUserId(), name, distributorCodes, projectCodes, productTypeCodes, supplierCodes,
                status, updaters, updateFrom, updateTo, pageNo, pageSize);
        RoutingRulePageQuery query = new RoutingRulePageQuery(name, distributorCodes, projectCodes,
                productTypeCodes, supplierCodes, ClosedEnums.parse(RoutingRuleStatus.class, status),
                updaters, updateFrom, updateTo, pageNo, pageSize);
        return success(queryService.page(query));
    }

    @GetMapping("/{ruleCode}")
    @PreAuthorize(ROUTING_RULE_QUERY_EXPRESSION)
    @Operation(summary = "查询当前生效版本内的一条标准商品路由规则")
    public CommonResult<RoutingRuleView> get(
            @Parameter(description = "策略版本内稳定规则编码")
            @Size(max = 64) @PathVariable String ruleCode) {
        log.info("路由规则详情入口 userId={} ruleCode={}", getLoginUserId(), ruleCode);
        return success(queryService.get(ruleCode));
    }

    @PostMapping
    @PreAuthorize(ROUTING_RULE_CREATE_EXPRESSION)
    @Operation(summary = "新增路由规则并立即补丁发布",
            description = "忽略 body.ruleCode；克隆当前生效版本打补丁后发布。"
                    + "启用集内规则名称与四级适用范围唯一；受保护规则仅可存在一条系统预置。"
                    + "见 docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#55。")
    public CommonResult<RoutingRuleView> create(@Valid @RequestBody RoutingRuleSaveReqVO request) {
        Long userId = getLoginUserId();
        log.info("路由规则新增入口 userId={} routeMode={} distributorCodes={} projectCodes={} "
                        + "productTypeCodes={} supplierCodes={}",
                userId, request.getRouteMode(), firstOrNull(request.getDistributorCodes()),
                firstOrNull(request.getProjectCodes()), firstOrNull(request.getProductTypeCodes()),
                firstOrNull(request.getSupplierCodes()));
        NormalizedRulePatch patch = saveSupport.validateAndNormalize(
                toSaveRequest(request), null, RoutingRuleSaveSupport.Operation.CREATE);
        return success(commandService.create(patch, userId));
    }

    @PutMapping("/{ruleCode}")
    @PreAuthorize(ROUTING_RULE_UPDATE_EXPRESSION)
    @Operation(summary = "整资源替换路由规则并立即补丁发布",
            description = "路径 ruleCode 写入新版本；body.ruleCode 若传须与路径一致。"
                    + "受保护 GLOBAL_DEFAULT 规则的范围与路由模式不可修改。")
    public CommonResult<RoutingRuleView> update(
            @Parameter(description = "策略版本内稳定规则编码")
            @Size(max = 64) @PathVariable String ruleCode,
            @Valid @RequestBody RoutingRuleSaveReqVO request) {
        Long userId = getLoginUserId();
        log.info("路由规则更新入口 userId={} ruleCode={} routeMode={} distributorCodes={} projectCodes={} "
                        + "productTypeCodes={} supplierCodes={}",
                userId, ruleCode, request.getRouteMode(), firstOrNull(request.getDistributorCodes()),
                firstOrNull(request.getProjectCodes()), firstOrNull(request.getProductTypeCodes()),
                firstOrNull(request.getSupplierCodes()));
        NormalizedRulePatch patch = saveSupport.validateAndNormalize(
                toSaveRequest(request), ruleCode, RoutingRuleSaveSupport.Operation.UPDATE);
        return success(commandService.update(ruleCode, patch, userId));
    }

    @DeleteMapping("/{ruleCode}")
    @PreAuthorize(ROUTING_RULE_DELETE_EXPRESSION)
    @Operation(summary = "删除路由规则并立即补丁发布",
            description = "从下一版本移除该规则；受保护 GLOBAL_DEFAULT 拒绝删除。")
    public CommonResult<Boolean> delete(
            @Parameter(description = "策略版本内稳定规则编码")
            @Size(max = 64) @PathVariable String ruleCode) {
        Long userId = getLoginUserId();
        log.info("路由规则删除入口 userId={} ruleCode={}", userId, ruleCode);
        commandService.delete(ruleCode, userId);
        return success(true);
    }

    @PutMapping("/{ruleCode}/status")
    @PreAuthorize(ROUTING_RULE_UPDATE_STATUS_EXPRESSION)
    @Operation(summary = "启停路由规则（真实状态）",
            description = "ENABLED/DISABLED 为规则真实启停状态（不再等同删除）；"
                    + "停用后该规则不参与匹配，无规则命中时回落全局默认规则。")
    public CommonResult<RoutingRuleView> changeStatus(
            @Parameter(description = "策略版本内稳定规则编码")
            @Size(max = 64) @PathVariable String ruleCode,
            @Valid @RequestBody ChangeStatusReqVO request) {
        Long userId = getLoginUserId();
        RoutingRuleStatus status = ClosedEnums.parse(RoutingRuleStatus.class, request.getStatus());
        log.info("路由规则启停入口 userId={} ruleCode={} status={}", userId, ruleCode, status);
        return success(commandService.changeStatus(ruleCode, status, userId));
    }

    private static SaveRequest toSaveRequest(RoutingRuleSaveReqVO request) {
        return new SaveRequest(
                request.getName(),
                request.getRuleCode(),
                ClosedEnums.parse(RoutingRuleRouteMode.class, request.getRouteMode()),
                ClosedEnums.parse(RoutingRuleStatus.class, request.getStatus()),
                ClosedEnums.parse(RoutingRuleScopeMode.class, request.getDistributorMode()),
                request.getDistributorCodes(),
                ClosedEnums.parse(RoutingRuleScopeMode.class, request.getProjectMode()),
                request.getProjectCodes(),
                ClosedEnums.parse(RoutingRuleScopeMode.class, request.getProductTypeMode()),
                request.getProductTypeCodes(),
                ClosedEnums.parse(RoutingRuleScopeMode.class, request.getSupplierMode()),
                request.getSupplierCodes(),
                request.getPrimarySupplierCode(),
                toPriorities(request.getPriorities()),
                toWeights(request.getWeights()),
                request.getMaxSupplyPriceCents());
    }

    private static List<PriorityInput> toPriorities(List<RoutingRuleSaveReqVO.PriorityItemReqVO> items) {
        if (items == null) {
            return null;
        }
        List<PriorityInput> result = new ArrayList<>(items.size());
        for (RoutingRuleSaveReqVO.PriorityItemReqVO item : items) {
            if (item == null) {
                result.add(null);
                continue;
            }
            result.add(new PriorityInput(item.getId(),
                    ClosedEnums.parse(RoutingRuleStrategy.class, item.getStrategy())));
        }
        return result;
    }

    private static WeightInput toWeights(RoutingRuleSaveReqVO.WeightReqVO weights) {
        if (weights == null) {
            return null;
        }
        return new WeightInput(weights.getStability(), weights.getPrice(), weights.getTimeliness());
    }

    private static String firstOrNull(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.getFirst();
    }

    /**
     * 启停请求体。设计见
     * {@code docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#56}。
     */
    @Data
    @Schema(description = "路由规则启停请求")
    public static class ChangeStatusReqVO {

        @NotBlank(message = "启停状态不能为空")
        @Schema(description = "目标启停状态 ENABLED/DISABLED",
                requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"ENABLED", "DISABLED"}, example = "DISABLED")
        private String status;
    }
}
