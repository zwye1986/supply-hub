package com.aifuli.module.supplychain.infrastructure.auth;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 有界缓存请求体包装器：先检查声明长度，再以最多 {@code limit + 1} 的探测语义流式读取真实内容。
 *
 * <p>包装完成后每次 {@link #getInputStream()} / {@link #getReader()} 都返回独立读取游标，供验签与
 * controller 复读；缓存字节只通过包级 {@link #bodyForDigest()} 交给同包摘要逻辑，不提供 public
 * 可变数组出口。超限异常不携带请求体内容。详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#61-projectappauthfilter}。</p>
 */
final class CachedBodyHttpServletRequestWrapper extends HttpServletRequestWrapper {

    private static final int READ_BUFFER_BYTES = 8192;

    private final byte[] body;

    CachedBodyHttpServletRequestWrapper(HttpServletRequest request, int maximumBodyBytes) throws IOException {
        super(request);
        if (maximumBodyBytes <= 0) {
            throw new IllegalArgumentException("请求体认证上限必须为正数");
        }
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > maximumBodyBytes) {
            throw new BodyTooLargeException();
        }
        this.body = readBounded(request, maximumBodyBytes, declaredLength);
    }

    /** 同包摘要入口；返回内部数组避免 1 MiB 请求在验签路径产生第二份敏感载荷副本。 */
    byte[] bodyForDigest() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        return new CachedServletInputStream(body);
    }

    @Override
    public BufferedReader getReader() throws IOException {
        String encoding = getCharacterEncoding();
        return new BufferedReader(new InputStreamReader(getInputStream(),
                encoding == null ? StandardCharsets.ISO_8859_1.name() : encoding));
    }

    /** 受保护接口均为同步 MVC；即使原始 request 声明支持异步，包装器也不得向下游透出该能力。 */
    @Override
    public boolean isAsyncSupported() {
        return false;
    }

    @Override
    public AsyncContext startAsync() {
        throw new IllegalStateException("项目认证请求仅支持同步处理");
    }

    @Override
    public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) {
        throw new IllegalStateException("项目认证请求仅支持同步处理");
    }

    private static byte[] readBounded(HttpServletRequest request, int limit, long declaredLength)
            throws IOException {
        int initialCapacity = declaredLength >= 0
                ? (int) Math.min(declaredLength, limit)
                : Math.min(READ_BUFFER_BYTES, limit);
        ByteArrayOutputStream output = new ByteArrayOutputStream(initialCapacity);
        byte[] buffer = new byte[Math.min(READ_BUFFER_BYTES, limit)];
        int total = 0;
        try (ServletInputStream input = request.getInputStream()) {
            while (true) {
                int remaining = limit - total;
                int requested = remaining == Integer.MAX_VALUE
                        ? buffer.length : Math.min(buffer.length, remaining + 1);
                int count = input.read(buffer, 0, requested);
                if (count == -1) {
                    return output.toByteArray();
                }
                if (count == 0) {
                    int single = input.read();
                    if (single == -1) {
                        return output.toByteArray();
                    }
                    if (remaining == 0) {
                        throw new BodyTooLargeException();
                    }
                    output.write(single);
                    total++;
                    continue;
                }
                if (count > remaining) {
                    throw new BodyTooLargeException();
                }
                output.write(buffer, 0, count);
                total += count;
            }
        }
    }

    /** 安全的内部控制流异常：不含请求体、声明长度或实际长度。 */
    static final class BodyTooLargeException extends IOException {

        private static final long serialVersionUID = 1L;

        private BodyTooLargeException() {
            super("请求体超过认证上限");
        }
    }

    /** 已缓存字节的同步 Servlet 输入流；明确拒绝 Servlet 非阻塞读取，避免伪造异步回调语义。 */
    private static final class CachedServletInputStream extends ServletInputStream {

        private final ByteArrayInputStream input;

        private CachedServletInputStream(byte[] body) {
            this.input = new ByteArrayInputStream(body);
        }

        @Override
        public int read() throws IOException {
            return input.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            return input.read(bytes, offset, length);
        }

        @Override
        public boolean isFinished() {
            return input.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            Objects.requireNonNull(readListener, "readListener");
            throw new IllegalStateException("项目认证请求不支持非阻塞读取");
        }
    }
}
