package com.aifuli.channel.taobaoflash.controller;

import com.aifuli.channel.taobaoflash.service.ChannelPaymentService;
import com.aifuli.channel.taobaoflash.service.ChannelRefundService;
import com.aifuli.module.supplychain.api.o2o.dto.PaymentPreparationTicketDTO;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import eleme.openapi.h5.sdk.pay.enums.ReturnCode;
import eleme.openapi.h5.sdk.pay.model.request.PayRequest;
import eleme.openapi.h5.sdk.pay.model.request.QueryPayRequest;
import eleme.openapi.h5.sdk.pay.model.request.RefundRequest;
import eleme.openapi.h5.sdk.pay.model.response.QueryPayResponse;
import eleme.openapi.h5.sdk.pay.model.response.RefundResponse;
import eleme.openapi.h5.sdk.pay.utils.PaySignUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * 淘宝闪购渠道支付入口：手动复刻 ele.me SDK BasePayService 的验签/签响应流程。
 *
 * <p>背景：eleme-openapi-h5-sdk 1.0.15-RELEASE 基于 javax.servlet（Spring Boot 2.x），
 * 而 aifuli 框架是 Spring Boot 3（jakarta.servlet），无法直接继承 BasePayService。
 * 因此本控制器用 jakarta.servlet 手动调用 PaySignUtil（静态工具，不依赖 servlet）完成验签与签响应，
 * 业务逻辑调供应链中台 O2OChannelCommandApi.preparePayment，用中台返回的 launchDescriptor 重定向。
 * 详见 docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.7。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/tbsg/channel")
public class ChannelPayController {

    private final ChannelPaymentService channelPaymentService;
    private final ChannelRefundService channelRefundService;

    @Value("${tbsg.pay.platform-public-key:}")
    private String platformPublicKey;

    @Value("${tbsg.pay.merchant-private-key:}")
    private String merchantPrivateKey;

    /**
     * ele.me 支付入口：验签 → 调中台 preparePayment → 重定向到采购商收银台。
     *
     * <p>eleme 平台以 form 参数提交，从 HttpServletRequest 取参数组装 content 验签；
     * 验签通过后解析 PayRequest 调中台，用中台 launchDescriptor sendRedirect（替代原海狸收银台）。</p>
     */
    @PostMapping("/pay")
    public String pay(HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> params = params(request);
        String content = JSON.toJSONString(params);
        log.info("[渠道支付] 收到 ele.me 支付请求, transactionId={}, payAmount={}, uid={}, notifyUrlPresent={}",
                params.get("transactionId"), params.get("payAmount"), params.get("uid"),
                params.get("notifyUrl") != null);

        if (!PaySignUtil.verify(content, platformPublicKey)) {
            log.warn("[渠道支付] 验签失败, transactionId={}", params.get("transactionId"));
            JSONObject json = new JSONObject();
            json.put("returnCode", ReturnCode.SIGN_ERROR.getCode());
            json.put("returnMsg", "签名验证失败");
            return PaySignUtil.sign(json, merchantPrivateKey);
        }

        PayRequest payRequest = JSON.parseObject(content, PayRequest.class);
        try {
            PaymentPreparationTicketDTO ticket = channelPaymentService.preparePayment(payRequest);
            if (ticket == null || ticket.getLaunchDescriptor() == null) {
                throw new IllegalStateException("中台 preparePayment 未返回收银台跳转描述");
            }
            log.info("[渠道支付] 重定向收银台 transactionId={}, outTradeNo={}, orderNo={}",
                    payRequest.getTransactionId(), ticket.getOutTradeNo(), ticket.getOrderNo());
            response.sendRedirect(ticket.getLaunchDescriptor());
            return null;
        } catch (Exception e) {
            log.error("[渠道支付] 处理失败, transactionId={}", payRequest.getTransactionId(), e);
            JSONObject json = new JSONObject();
            json.put("returnCode", ReturnCode.SYSTEM_ERROR.getCode());
            json.put("returnMsg", "渠道支付处理失败：" + e.getMessage());
            return PaySignUtil.sign(json, merchantPrivateKey);
        }
    }

    /**
     * ele.me 支付查询：验签后按本地渠道交易表投影支付状态。
     *
     * <p>JSON body 提交（与 refund 相同），返回加签后的 QueryPayResponse。</p>
     */
    @PostMapping("/queryPay")
    public String queryPay(@RequestBody String content) {
        JSONObject requestJson = JSON.parseObject(content);
        log.info("[渠道支付] 收到 ele.me queryPay, transactionId={}", requestJson.get("transactionId"));
        if (!PaySignUtil.verify(content, platformPublicKey)) {
            log.warn("[渠道支付] queryPay 验签失败");
            JSONObject json = new JSONObject();
            json.put("returnCode", ReturnCode.SIGN_ERROR.getCode());
            json.put("returnMsg", "签名验证失败");
            return PaySignUtil.sign(json, merchantPrivateKey);
        }
        try {
            QueryPayRequest queryRequest = JSON.parseObject(content, QueryPayRequest.class);
            QueryPayResponse response = channelPaymentService.queryPay(queryRequest);
            return PaySignUtil.sign(response, merchantPrivateKey);
        } catch (Exception e) {
            log.error("[渠道支付] queryPay 处理失败", e);
            JSONObject json = new JSONObject();
            json.put("returnCode", ReturnCode.SYSTEM_ERROR.getCode());
            json.put("returnMsg", "渠道支付查询失败：" + e.getMessage());
            return PaySignUtil.sign(json, merchantPrivateKey);
        }
    }

    private Map<String, Object> params(HttpServletRequest request) {
        Map<String, Object> ret = new HashMap<>();
        Enumeration<String> names = request.getParameterNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            ret.put(name, request.getParameter(name));
        }
        return ret;
    }

    /**
     * ele.me 退款入口：验签 → 调中台 acceptRefund → 落本地退款记录 → 签响应返回 RefundResponse。
     *
     * <p>eleme 平台以 JSON body 提交（与 pay 的 form 参数不同），验签通过后解析 RefundRequest 调
     * {@link ChannelRefundService#acceptRefund}，返回的 RefundResponse 经 PaySignUtil.sign 签名后返回。</p>
     */
    @PostMapping("/refund")
    public String refund(@RequestBody String content) {
        JSONObject requestJson = JSON.parseObject(content);
        log.info("[渠道退款] 收到 ele.me 退款请求, refundNo={}, transactionId={}, refundAmount={}",
                requestJson.get("refundNo"), requestJson.get("transactionId"), requestJson.get("refundAmount"));

        if (!PaySignUtil.verify(content, platformPublicKey)) {
            log.warn("[渠道退款] 验签失败");
            JSONObject json = new JSONObject();
            json.put("returnCode", ReturnCode.SIGN_ERROR.getCode());
            json.put("returnMsg", "签名验证失败");
            return PaySignUtil.sign(json, merchantPrivateKey);
        }

        try {
            RefundRequest refundRequest = JSON.parseObject(content, RefundRequest.class);
            RefundResponse response = channelRefundService.acceptRefund(refundRequest);
            log.info("[渠道退款] 受理完成 refundNo={}, outRefundNo={}, refundStatus={}",
                    response.getRefundNo(), response.getOutRefundNo(), response.getRefundStatus());
            return PaySignUtil.sign(response, merchantPrivateKey);
        } catch (Exception e) {
            log.error("[渠道退款] 处理失败", e);
            JSONObject json = new JSONObject();
            json.put("returnCode", ReturnCode.SYSTEM_ERROR.getCode());
            json.put("returnMsg", "渠道退款处理失败：" + e.getMessage());
            return PaySignUtil.sign(json, merchantPrivateKey);
        }
    }
}
