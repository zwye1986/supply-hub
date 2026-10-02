package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.enums.UserTypeEnum;
import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.security.core.util.SecurityFrameworkUtils;
import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyMonitorQueryService;
import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyAdminCommandService;
import com.aifuli.module.supplychain.interfaceapi.admin.vo.RechargeFrequencyAdminVO.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.*;

/**
 * 仅内部运营ADMIN用户可读全中台限频队列。每个HTTP映射独立授权，鉴权在查审计和资源前完成。
 * 不信任请求中的操作人/未发送勾选；普通订单权限和项目应用身份不能取得本接口资格。
 * 所有成功响应禁止缓存；错误响应由登记的敏感路径保护正文与日志。
 * 设计见 {@code docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#7-管理端契约与权限}。
 */
@RestController @RequestMapping("/supply-chain/recharge-frequency") @Validated @RequiredArgsConstructor
public class RechargeFrequencyAdminController {
    private final RechargeFrequencyMonitorQueryService query;
    private final RechargeFrequencyAdminCommandService commands;

    @GetMapping("/statistics") @PreAuthorize(RECHARGE_FREQUENCY_QUERY_EXPRESSION)
    public ResponseEntity<CommonResult<RechargeFrequencyMonitorQueryService.Statistics>> statistics() {
        return safe(query.statistics(actor()));
    }
    @GetMapping("/waiting-orders") @PreAuthorize(RECHARGE_FREQUENCY_QUERY_EXPRESSION)
    public ResponseEntity<CommonResult<RechargeFrequencyMonitorQueryService.ObservedPage<?>>> waiting(@Valid PageRequest request) {
        return safe(query.page(actor(), RechargeFrequencyMonitorQueryService.View.WAITING, filter(request), null));
    }
    @GetMapping("/occupancies") @PreAuthorize(RECHARGE_FREQUENCY_QUERY_EXPRESSION)
    public ResponseEntity<CommonResult<RechargeFrequencyMonitorQueryService.ObservedPage<?>>> occupancies(@Valid PageRequest request) {
        return safe(query.page(actor(), RechargeFrequencyMonitorQueryService.View.OCCUPANCIES, filter(request), null));
    }
    @GetMapping("/backlogs") @PreAuthorize(RECHARGE_FREQUENCY_QUERY_EXPRESSION)
    public ResponseEntity<CommonResult<RechargeFrequencyMonitorQueryService.ObservedPage<?>>> backlogs(@Valid PageRequest request) {
        return safe(query.page(actor(), RechargeFrequencyMonitorQueryService.View.BACKLOGS, filter(request), null));
    }
    @PostMapping("/search") @PreAuthorize(RECHARGE_FREQUENCY_QUERY_EXPRESSION)
    public ResponseEntity<CommonResult<RechargeFrequencyMonitorQueryService.ObservedPage<?>>> search(@Valid @RequestBody SearchRequest request) {
        long operator=actor();
        return safe(query.page(operator, RechargeFrequencyMonitorQueryService.View.valueOf(request.getView().name()), filter(request), request.getMobile()));
    }
    @PostMapping("/entries/{entryId}/retry") @PreAuthorize(RECHARGE_FREQUENCY_RETRY_EXPRESSION)
    public ResponseEntity<CommonResult<RechargeFrequencyAdminCommandService.ActionResult>> retry(@PathVariable("entryId") @Positive Long entryId,
                                                                                               @Valid @RequestBody RetryRequest request) {
        return safe(commands.retry(actor(),entryId,new RechargeFrequencyAdminCommandService.RetryCommand(request.actionNo(),request.expectedVersion(),request.reason())));
    }
    @PostMapping("/queues/{queueId}/release") @PreAuthorize(RECHARGE_FREQUENCY_RELEASE_EXPRESSION)
    public ResponseEntity<CommonResult<RechargeFrequencyAdminCommandService.ActionResult>> release(@PathVariable("queueId") @Positive Long queueId,
                                                                                                 @Valid @RequestBody ReleaseRequest request) {
        return safe(commands.release(actor(),queueId,new RechargeFrequencyAdminCommandService.ReleaseCommand(request.actionNo(),request.expectedHolderOrderNo(),request.expectedGeneration(),request.expectedVersion(),request.reason())));
    }
    private static long actor() {
        var user=SecurityFrameworkUtils.getLoginUser();
        if(user==null || user.getId()==null || user.getId()<=0 || !java.util.Objects.equals(user.getUserType(),UserTypeEnum.ADMIN.getValue())) {
            throw new AccessDeniedException("内部运营身份必需");
        }
        return user.getId();
    }
    private static RechargeFrequencyMonitorQueryService.Filter filter(PageRequest r) {
        var request=com.aifuli.framework.common.util.servlet.ServletUtils.getRequest();
        if(request!=null && request.getParameterMap().containsKey("mobile")) {
            throw com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception(
                    com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.FREQUENCY_PARAMETER_INVALID);
        }
        return new RechargeFrequencyMonitorQueryService.Filter(r.getPageNo(),r.getPageSize(),r.getBuyerId(),r.getProjectId(),r.getPlatformSpuCode(),
                r.getOrderNo(),r.getBuyerOrderNo(),r.getEnqueuedFrom(),r.getEnqueuedTo(),null,null);
    }
    private static <T> ResponseEntity<CommonResult<T>> safe(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(CommonResult.success(data));
    }
}
