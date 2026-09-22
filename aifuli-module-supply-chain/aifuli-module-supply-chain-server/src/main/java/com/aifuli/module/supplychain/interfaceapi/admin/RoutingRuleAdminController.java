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
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.ADMIN_EXPRESSION;

/**
 * 管理端路由规则：GET 投影当前生效版本；写入口经补丁发布落库。
 * <p>设计见 {@code docs/superpowers/specs/2026-09-21-routing-rule-admin-write-design.md#3-rest}；
 * GET 字段映射见 {@code docs/superpowers/specs/2026-09-21-routing-rule-admin-readonly-design.md#3-rest}。
 */
@Slf4j
@Tag(name = "管理后台 - 供应链路由规则")
@RestController
@RequestMapping("/supply-chain/routing-rules")
@Validated
@RequiredArgsConstructor
@PreAuthorize(ADMIN_EXPRESSION)
public class RoutingRuleAdminController {

    private final RoutingRuleAdminCommandService commandService;
    private final RoutingRuleAdminQueryService queryService;
    /** SaveSupport 无 Spring 依赖，控制器内直接实例化。 */
    private final RoutingRuleSaveSupport saveSupport = new RoutingRuleSaveSupport();

    @GetMapping
    @Operation(summary = "分页查询当前生效的标准商品路由规则",
            description = "只读启用中 STANDARD_PRODUCT 策略的当前生效已发布版本。"
                    + "项目/商品类型固定 ALL；价格/综合优先级与权重仅为占位。"
                    + "写操作见 docs/superpowers/specs/2026-09-21-routing-rule-admin-write-design.md。")
    public CommonResult<PageResult<RoutingRuleView>> page(
            @Parameter(description = "规则中文名称，包含匹配")
            @Size(max = 128) @RequestParam(required = false) String name,
            @Parameter(description = "分销商业务编码；ALL 规则始终命中")
            @RequestParam(required = false) List<String> distributorCodes,
            @Parameter(description = "项目业务编码；本期接受但不过滤")
            @RequestParam(required = false) List<String> projectCodes,
            @Parameter(description = "商品类型编码；本期接受但不过滤")
            @RequestParam(required = false) List<String> productTypeCodes,
            @Parameter(description = "候选供应商业务编码，求交")
            @RequestParam(required = false) List<String> supplierCodes,
            @Parameter(description = "启停展示值 ENABLED/DISABLED")
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
        log.info("路由规则分页入口 userId={} name={} distributorCodes={} supplierCodes={} status={} "
                        + "updaters={} updateFrom={} updateTo={} pageNo={} pageSize={}",
                getLoginUserId(), name, distributorCodes, supplierCodes, status, updaters,
                updateFrom, updateTo, pageNo, pageSize);
        RoutingRulePageQuery query = new RoutingRulePageQuery(name, distributorCodes, projectCodes,
                productTypeCodes, supplierCodes, ClosedEnums.parse(RoutingRuleStatus.class, status),
                updaters, updateFrom, updateTo, pageNo, pageSize);
        return success(queryService.page(query));
    }

    @GetMapping("/{ruleCode}")
    @Operation(summary = "查询当前生效版本内的一条标准商品路由规则")
    public CommonResult<RoutingRuleView> get(
            @Parameter(description = "策略版本内稳定规则编码")
            @Size(max = 64) @PathVariable String ruleCode) {
        log.info("路由规则详情入口 userId={} ruleCode={}", getLoginUserId(), ruleCode);
        return success(queryService.get(ruleCode));
    }

    @PostMapping
    @Operation(summary = "新增路由规则并立即补丁发布",
            description = "忽略 body.ruleCode；克隆当前生效版本打补丁后发布。"
                    + "见 docs/superpowers/specs/2026-09-21-routing-rule-admin-write-design.md#32-新增。")
    public CommonResult<RoutingRuleView> create(@Valid @RequestBody RoutingRuleSaveReqVO request) {
        Long userId = getLoginUserId();
        log.info("路由规则新增入口 userId={} routeMode={} buyerCode={} skuCode={} supplierCodes={}",
                userId, request.getRouteMode(), firstOrNull(request.getDistributorCodes()),
                request.getSkuCode(), request.getSupplierCodes());
        NormalizedRulePatch patch = saveSupport.validateAndNormalize(
                toSaveRequest(request), null, RoutingRuleSaveSupport.Operation.CREATE);
        return success(commandService.create(patch, userId));
    }

    @PutMapping("/{ruleCode}")
    @Operation(summary = "整资源替换路由规则并立即补丁发布",
            description = "路径 ruleCode 写入新版本；body.ruleCode 若传须与路径一致。"
                    + "见 docs/superpowers/specs/2026-09-21-routing-rule-admin-write-design.md#33-整资源替换。")
    public CommonResult<RoutingRuleView> update(
            @Parameter(description = "策略版本内稳定规则编码")
            @Size(max = 64) @PathVariable String ruleCode,
            @Valid @RequestBody RoutingRuleSaveReqVO request) {
        Long userId = getLoginUserId();
        log.info("路由规则更新入口 userId={} ruleCode={} routeMode={} buyerCode={} skuCode={} supplierCodes={}",
                userId, ruleCode, request.getRouteMode(), firstOrNull(request.getDistributorCodes()),
                request.getSkuCode(), request.getSupplierCodes());
        NormalizedRulePatch patch = saveSupport.validateAndNormalize(
                toSaveRequest(request), ruleCode, RoutingRuleSaveSupport.Operation.UPDATE);
        return success(commandService.update(ruleCode, patch, userId));
    }

    @DeleteMapping("/{ruleCode}")
    @Operation(summary = "删除路由规则并立即补丁发布",
            description = "从下一版本移除该规则；最后一条拒绝。"
                    + "见 docs/superpowers/specs/2026-09-21-routing-rule-admin-write-design.md#34-删除。")
    public CommonResult<Boolean> delete(
            @Parameter(description = "策略版本内稳定规则编码")
            @Size(max = 64) @PathVariable String ruleCode) {
        Long userId = getLoginUserId();
        log.info("路由规则删除入口 userId={} ruleCode={}", userId, ruleCode);
        commandService.delete(ruleCode, userId);
        return success(true);
    }

    @PutMapping("/{ruleCode}/status")
    @Operation(summary = "启停路由规则（前端兼容）",
            description = "DISABLED 等同删除并发布；ENABLED 为空操作仅返回当前投影。"
                    + "见 docs/superpowers/specs/2026-09-21-routing-rule-admin-write-design.md#35-启停前端兼容。")
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
                request.getDistributorCodes(),
                request.getSkuCode(),
                request.getFulfillmentType(),
                ClosedEnums.parse(RoutingRuleRouteMode.class, request.getRouteMode()),
                request.getPrimarySupplierCode(),
                request.getSupplierCodes(),
                ClosedEnums.parse(RoutingRuleScopeMode.class, request.getSupplierMode()),
                ClosedEnums.parse(RoutingRuleScopeMode.class, request.getDistributorMode()),
                ClosedEnums.parse(RoutingRuleStatus.class, request.getStatus()),
                request.getRuleCode(),
                request.getProjectCodes(),
                ClosedEnums.parse(RoutingRuleScopeMode.class, request.getProjectMode()),
                request.getProductTypeCodes(),
                ClosedEnums.parse(RoutingRuleScopeMode.class, request.getProductTypeMode()),
                toPriorities(request.getPriorities()),
                toWeights(request.getWeights()));
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
     * {@code docs/superpowers/specs/2026-09-21-routing-rule-admin-write-design.md#35-启停前端兼容}。
     */
    @Data
    @Schema(description = "路由规则启停请求")
    public static class ChangeStatusReqVO {

        @NotBlank(message = "启停状态不能为空")
        @Schema(description = "目标启停展示值 ENABLED/DISABLED",
                requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"ENABLED", "DISABLED"}, example = "DISABLED")
        private String status;
    }
}
