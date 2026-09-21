package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.routing.application.RoutingRuleAdminQueryService;
import com.aifuli.module.supplychain.routing.application.RoutingRuleAdminQueryService.RoutingRulePageQuery;
import com.aifuli.module.supplychain.routing.application.RoutingRuleAdminQueryService.RoutingRuleView;
import com.aifuli.module.supplychain.routing.application.RoutingRuleStatus;
import com.aifuli.module.supplychain.tradecore.profile.domain.ClosedEnums;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.framework.common.pojo.CommonResult.success;
import static com.aifuli.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_WRITE_NOT_OPEN;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.ADMIN_EXPRESSION;

/**
 * 管理端路由规则只读投影。写入口仅为对齐前端已写路径而拒绝。
 * 设计见 {@code docs/superpowers/specs/2026-09-21-routing-rule-admin-readonly-design.md#3-rest}。
 */
@Slf4j
@Tag(name = "管理后台 - 供应链路由规则")
@RestController
@RequestMapping("/supply-chain/routing-rules")
@Validated
@RequiredArgsConstructor
@PreAuthorize(ADMIN_EXPRESSION)
public class RoutingRuleAdminController {

    private final RoutingRuleAdminQueryService queryService;

    @GetMapping
    @Operation(summary = "分页查询当前生效的标准商品路由规则",
            description = "只读启用中 STANDARD_PRODUCT 策略的当前生效已发布版本。"
                    + "项目/商品类型固定 ALL；价格/综合优先级与权重仅为占位。写操作一律拒绝。")
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
    @Operation(summary = "新增路由规则（本期拒绝）",
            description = "前端关 mock 后收到业务错误，避免把综合排序当已生效。真要改候选走 /routing-policies 发版本。")
    public CommonResult<Void> create() {
        return rejectWrite("create", null);
    }

    @PutMapping("/{ruleCode}")
    @Operation(summary = "修改路由规则（本期拒绝）")
    public CommonResult<Void> update(
            @Parameter(description = "策略版本内稳定规则编码")
            @Size(max = 64) @PathVariable String ruleCode) {
        return rejectWrite("update", ruleCode);
    }

    @DeleteMapping("/{ruleCode}")
    @Operation(summary = "删除路由规则（本期拒绝）")
    public CommonResult<Void> delete(
            @Parameter(description = "策略版本内稳定规则编码")
            @Size(max = 64) @PathVariable String ruleCode) {
        return rejectWrite("delete", ruleCode);
    }

    @PutMapping("/{ruleCode}/status")
    @Operation(summary = "启停路由规则（本期拒绝）",
            description = "仅为对齐前端已写 /status 路径；不是真启停。")
    public CommonResult<Void> changeStatus(
            @Parameter(description = "策略版本内稳定规则编码")
            @Size(max = 64) @PathVariable String ruleCode) {
        return rejectWrite("changeStatus", ruleCode);
    }

    private CommonResult<Void> rejectWrite(String action, String ruleCode) {
        log.info("路由规则写拒绝 userId={} action={} ruleCode={}", getLoginUserId(), action, ruleCode);
        throw exception(ROUTING_RULE_WRITE_NOT_OPEN);
    }
}
