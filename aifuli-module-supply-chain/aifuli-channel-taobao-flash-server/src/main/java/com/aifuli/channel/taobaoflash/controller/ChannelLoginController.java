package com.aifuli.channel.taobaoflash.controller;

import com.aifuli.channel.taobaoflash.dto.ChannelH5UrlResponse;
import com.aifuli.channel.taobaoflash.dto.ChannelSsoH5UrlRequest;
import com.aifuli.channel.taobaoflash.service.ChannelLoginService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * 渠道登录与 SSO 免登录入口：迁移自原 yudao-module-taobao TbsgLoginController。
 *
 * <p>本期保留 /tbsg/channel/sso/getTbsgH5Url 供联调；中台 BuyerO2OEntryApi 后续可改为调本端点。
 * 详见 docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.7。</p>
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/tbsg/channel")
@CrossOrigin(origins = "*", methods = {RequestMethod.POST, RequestMethod.OPTIONS})
public class ChannelLoginController {

    private final ChannelLoginService channelLoginService;

    /**
     * 外部商城 SSO 免登录获取淘宝闪购 H5 链接（服务端到服务端，带签名）。
     */
    @PostMapping(value = "/sso/getTbsgH5Url", consumes = "application/json", produces = "application/json")
    public ResponseEntity<ChannelH5UrlResponse> getH5UrlBySso(@Valid @RequestBody ChannelSsoH5UrlRequest request) {
        log.info("[SSO免登录] 收到请求, externalUserId={}, env={}, variant={}",
                request.getExternalUserId(), request.getEnv(), request.getVariant());
        ChannelH5UrlResponse response = channelLoginService.generateH5UrlBySso(request);
        if ("SUCCESS".equals(response.getStatus())) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.badRequest().body(response);
    }
}
