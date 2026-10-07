package com.aifuli.module.supplychain.supplieradapter.huasheng.http;

import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalAttempt;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengTransportContext;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengWireRequest;

/**
 * 华昇 HTTP 物理传输端口接口。
 *
 * <p>职责：
 * 纯双参物理传输端口契约，仅接受不可变线缆请求 {@link HuashengWireRequest} 与传输上下文 {@link HuashengTransportContext}，
 * 返回受保护的内部通信尝试结果 {@link HuashengInternalAttempt}。
 *
 * <p>不变量：
 * <ul>
 *   <li>无单参重载，无 Map/String URL 发送旁路；</li>
 *   <li>禁止客户端直接注入，仅产出通信事实 {@link HuashengHttpExchange}；</li>
 *   <li>不暴露 OkHttp 或底层网络实现细节。</li>
 * </ul>
 *
 * <p>关键决策：
 * 六边形架构出站传输端口，将协议层与物理网络彻底解耦。
 *
 * <p>设计路径与章节锚点：
 * docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#172-纯协议传输上下文与双参端口。
 */
public interface HuashengHttpTransportPort {

    /**
     * 执行物理 HTTP 交换。
     *
     * @param request 华昇线缆请求，非空
     * @param transportContext 华昇传输上下文，非空
     * @return 包含 {@link HuashengHttpExchange} 或受保护失败的内部尝试结果
     */
    HuashengInternalAttempt<HuashengHttpExchange> exchange(
            HuashengWireRequest request, HuashengTransportContext transportContext);
}
