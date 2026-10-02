package com.aifuli.module.supplychain.interfaceapi.open.project;

import com.aifuli.framework.common.exception.enums.GlobalErrorCodeConstants;
import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.auth.ProjectAuthOperations;
import com.aifuli.module.supplychain.api.fulfillment.dto.ProjectStandardOrderAssetsRespDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.ProjectStandardOrderCreateRespDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.ProjectStandardOrderQueryRespDTO;
import com.aifuli.module.supplychain.application.facade.ProjectStandardOrderCommandFacade;
import com.aifuli.module.supplychain.application.facade.ProjectStandardOrderQueryFacade;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor.StandardProductContext;
import com.aifuli.module.supplychain.interfaceapi.open.project.dto.ProjectStandardOrderCreateReqDTO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * 项目标准订单开放 HTTP 控制器。
 *
 * <p>登记于 {@link ProjectAuthOperations} 的三个精确 POST 操作入口：
 * <ul>
 *   <li>{@code POST /open-api/supply-chain/project/v1/orders}：标准下单</li>
 *   <li>{@code POST /open-api/supply-chain/project/v1/orders/query}：标准查单</li>
 *   <li>{@code POST /open-api/supply-chain/project/v1/orders/assets/query}：标准资产查询</li>
 * </ul>
 * 采用严格的 byte[] 接收与专用解析器，非空 query string 统一拒绝，不直接访问 DAL 或供应商。
 * 详见 {@code docs/superpowers/specs/2026-10-02-standard-project-http-transport-design.md#1-controller与严格请求解析}。</p>
 */
@Slf4j
@RestController
@RequestMapping(ProjectAuthOperations.STANDARD_ORDERS_PATH)
@RequiredArgsConstructor
public class ProjectStandardOrderController {

    private final ProjectCallContextAccessor contextAccessor;
    private final ProjectStandardOrderCommandFacade commandFacade;
    private final ProjectStandardOrderQueryFacade queryFacade;
    private final ProjectStandardHttpRequestParser parser;

    /**
     * 标准商品项目下单。
     *
     * @param request HTTP 请求对象，用于校验 query string
     * @param rawBody 经过 HMAC 验签的原始正文
     * @return 封闭的标准下单回执响应
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public CommonResult<ProjectStandardOrderCreateRespDTO> createOrder(
            HttpServletRequest request,
            @RequestBody byte[] rawBody) {
        requireNoQueryString(request);
        StandardProductContext context = contextAccessor.requireStandardProductContext(
                ProjectAuthOperations.STANDARD_ORDER_CREATE);
        ProjectStandardOrderCreateReqDTO reqDTO = parser.parseCreate(rawBody);
        return CommonResult.success(commandFacade.create(context, reqDTO));
    }

    /**
     * 标准商品项目查单。
     *
     * @param request HTTP 请求对象，用于校验 query string
     * @param rawBody 经过 HMAC 验签的原始正文
     * @return 封闭的标准订单状态响应
     */
    @PostMapping(value = "/query", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CommonResult<ProjectStandardOrderQueryRespDTO> queryOrder(
            HttpServletRequest request,
            @RequestBody byte[] rawBody) {
        requireNoQueryString(request);
        StandardProductContext context = contextAccessor.requireStandardProductContext(
                ProjectAuthOperations.STANDARD_ORDER_QUERY);
        String buyerOrderNo = parser.parseQuery(rawBody);
        return CommonResult.success(queryFacade.query(context, buyerOrderNo));
    }

    /**
     * 标准商品项目资产查询。
     *
     * @param request HTTP 请求对象，用于校验 query string
     * @param rawBody 经过 HMAC 验签的原始正文
     * @return 封闭的标准卡券资产明细响应
     */
    @PostMapping(value = "/assets/query", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CommonResult<ProjectStandardOrderAssetsRespDTO> queryAssets(
            HttpServletRequest request,
            @RequestBody byte[] rawBody) {
        requireNoQueryString(request);
        StandardProductContext context = contextAccessor.requireStandardProductContext(
                ProjectAuthOperations.STANDARD_ORDER_ASSET_QUERY);
        String buyerOrderNo = parser.parseAssetsQuery(rawBody);
        return CommonResult.success(queryFacade.assets(context, buyerOrderNo));
    }

    private static void requireNoQueryString(HttpServletRequest request) {
        if (request != null && request.getQueryString() != null && !request.getQueryString().isBlank()) {
            log.debug("[项目标准订单] 拒绝携带 query string 的请求: {}", request.getRequestURI());
            throw exception(GlobalErrorCodeConstants.BAD_REQUEST);
        }
    }
}
