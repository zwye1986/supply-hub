package com.aifuli.channel.taobaoflash.mock;

import com.aifuli.channel.taobaoflash.mock.dto.CashierPayRequest;
import com.aifuli.channel.taobaoflash.mock.dto.CashierPayResponse;
import com.aifuli.channel.taobaoflash.mock.dto.MockElemeRequest;
import com.aifuli.channel.taobaoflash.mock.dto.MockElemeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * 假饿了么联调入口：仅支付闭环（pay / queryPay / cashierPay / notify/pay）。
 *
 * <p>开关 {@code tbsg.mock-eleme.enabled=true}。详见 {@code docs/集成测试.md}。</p>
 */
@RestController
@RequestMapping("/tbsg/mock-eleme")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "tbsg.mock-eleme.enabled", havingValue = "true")
public class MockElemeController {

    private final MockElemeService mockElemeService;

    @PostMapping("/pay")
    public MockElemeResponse pay(@RequestBody(required = false) MockElemeRequest request) {
        return mockElemeService.pay(currentContextBaseUrl(), request);
    }

    @PostMapping("/queryPay")
    public MockElemeResponse queryPay(@RequestBody(required = false) MockElemeRequest request) {
        return mockElemeService.queryPay(currentContextBaseUrl(), request);
    }

    @PostMapping(value = "/notify/pay", consumes = MediaType.ALL_VALUE)
    public String payNotify(@RequestBody(required = false) String body) {
        return mockElemeService.handlePayNotify(body);
    }

    @PostMapping("/cashierPay")
    public CashierPayResponse cashierPay(@RequestBody(required = false) CashierPayRequest request) {
        return mockElemeService.cashierPay(request);
    }

    private String currentContextBaseUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }
}
