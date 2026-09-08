package com.aifuli.module.supplychain.infrastructure.auth;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 有界请求体包装器单测：验证声明长度与真实流双重限流、复读以及 ServletInputStream 契约。
 *
 * <p>详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#61-projectappauthfilter}。</p>
 */
class CachedBodyHttpServletRequestWrapperTest {

    @Test
    void acceptsExactLimitAndSupportsIndependentStreamAndReaderReads() throws Exception {
        byte[] body = "你好-body".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = request(body);
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());

        CachedBodyHttpServletRequestWrapper wrapper =
                new CachedBodyHttpServletRequestWrapper(request, body.length);

        assertThat(readAll(wrapper.getInputStream())).isEqualTo(body);
        assertThat(readAll(wrapper.getInputStream())).isEqualTo(body);
        assertThat(wrapper.getReader().readLine()).isEqualTo("你好-body");
        assertThat(wrapper.bodyForDigest()).isEqualTo(body);
    }

    @Test
    void rejectsDeclaredContentLengthAboveLimitWithoutOpeningBodyStream() {
        AtomicInteger inputStreamCalls = new AtomicInteger();
        MockHttpServletRequest request = new MockHttpServletRequest() {
            @Override
            public long getContentLengthLong() {
                return 9;
            }

            @Override
            public ServletInputStream getInputStream() {
                inputStreamCalls.incrementAndGet();
                throw new AssertionError("声明长度已超限时不得打开请求流");
            }
        };

        assertThatThrownBy(() -> new CachedBodyHttpServletRequestWrapper(request, 8))
                .isInstanceOf(CachedBodyHttpServletRequestWrapper.BodyTooLargeException.class)
                .hasMessageNotContaining("body");
        assertThat(inputStreamCalls).hasValue(0);
    }

    @Test
    void rejectsChunkedBodyAboveLimitWithBoundedRead() {
        byte[] body = new byte[65];
        Arrays.fill(body, (byte) 'x');
        MockHttpServletRequest request = request(body);
        request.removeHeader("Content-Length");

        assertThatThrownBy(() -> new CachedBodyHttpServletRequestWrapper(request, 64))
                .isInstanceOf(CachedBodyHttpServletRequestWrapper.BodyTooLargeException.class);
    }

    @Test
    void rejectsForgedSmallerContentLengthWhenActualStreamExceedsLimit() {
        byte[] body = new byte[33];
        MockHttpServletRequest request = request(body);
        request.removeHeader("Content-Length");
        request.addHeader("Content-Length", "1");

        assertThatThrownBy(() -> new CachedBodyHttpServletRequestWrapper(request, 32))
                .isInstanceOf(CachedBodyHttpServletRequestWrapper.BodyTooLargeException.class);
    }

    @Test
    void synchronousWrapperRejectsAsyncAndNonBlockingReadListenerOnEveryCachedStream() throws Exception {
        MockHttpServletRequest delegate = request(new byte[]{1, 2});
        delegate.setAsyncSupported(true);
        CachedBodyHttpServletRequestWrapper wrapper =
                new CachedBodyHttpServletRequestWrapper(delegate, 2);
        ServletInputStream first = wrapper.getInputStream();
        ServletInputStream second = wrapper.getInputStream();
        AtomicInteger dataAvailable = new AtomicInteger();
        AtomicInteger allDataRead = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        ReadListener listener = new ReadListener() {
            @Override
            public void onDataAvailable() {
                dataAvailable.incrementAndGet();
            }

            @Override
            public void onAllDataRead() {
                allDataRead.incrementAndGet();
            }

            @Override
            public void onError(Throwable throwable) {
                errors.incrementAndGet();
            }
        };

        assertThat(wrapper.isAsyncSupported()).isFalse();
        assertThatThrownBy(wrapper::startAsync).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> wrapper.startAsync(wrapper, new MockHttpServletResponse()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(first.isReady()).isTrue();
        assertThat(first.isFinished()).isFalse();
        assertThatThrownBy(() -> first.setReadListener(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> first.setReadListener(listener)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> second.setReadListener(listener)).isInstanceOf(IllegalStateException.class);
        assertThat(dataAvailable).hasValue(0);
        assertThat(allDataRead).hasValue(0);
        assertThat(errors).hasValue(0);
        assertThat(readAll(first)).containsExactly(1, 2);
        assertThat(first.isFinished()).isTrue();
    }

    private static MockHttpServletRequest request(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(body);
        return request;
    }

    private static byte[] readAll(ServletInputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
