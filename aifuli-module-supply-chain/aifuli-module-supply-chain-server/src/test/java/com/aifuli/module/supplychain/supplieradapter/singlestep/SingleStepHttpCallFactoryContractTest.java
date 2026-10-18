package com.aifuli.module.supplychain.supplieradapter.singlestep;

import com.aifuli.module.supplychain.border.fulfillment.domain.SingleStepTimeoutContract;
import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 通过真实本地HTTP验证单步骤调用预算，不把阶段超时当成整次预算。
 *
 * <p>短时限仅为隔离夹具；不证明数据库认领、供应商许可或资金收敛。
 * 设计：docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#46-http预算与租约的本地实施切片。</p>
 */
@Timeout(10)
class SingleStepHttpCallFactoryContractTest {

    private final SingleStepHttpCallFactory factory = new SingleStepHttpCallFactory();
    private MockWebServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    @Test
    void shouldExposeApprovedInitialContract() {
        SingleStepTimeoutContract initial = SingleStepTimeoutContract.initial();

        assertThat(initial.connectTimeoutMs()).isEqualTo(10_000);
        assertThat(initial.readWriteTimeoutMs()).isEqualTo(20_000);
        assertThat(initial.totalCallTimeoutMs()).isEqualTo(30_000);
        assertThat(initial.executionLeaseMs()).isEqualTo(60_000);
    }

    @ParameterizedTest
    @CsvSource({"0,20,30,60", "-1,20,30,60", "10,0,30,60", "1,1,0,60",
            "31,20,30,60", "10,31,30,60", "10,20,30,30"})
    void shouldRejectInvalidFrozenContract(int connect, int readWrite, int total, int lease) {
        assertThatThrownBy(() -> new SingleStepTimeoutContract(connect, readWrite, total, lease))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldPreserveFormAndJsonAndReturnNonBusinessHttpResult(boolean form) throws Exception {
        RequestBody body = form
                ? new FormBody.Builder().add("value", "a+b&c=1").build()
                : RequestBody.create("{\"value\":\"a+b&c=1\"}", MediaType.get("application/json"));
        Request request = new Request.Builder().url(server.url("/original-order")).post(body).build();
        server.enqueue(new MockResponse().setResponseCode(422).setBody("not-a-trusted-business-result"));

        try (Response response = factory.newCall(request, SingleStepTimeoutContract.initial(),
                deadlineAfterMillis(60_000)).execute()) {
            assertThat(response.code()).isEqualTo(422);
            assertThat(response.body().string()).isEqualTo("not-a-trusted-business-result");
        }

        var received = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.getMethod()).isEqualTo("POST");
        assertThat(received.getPath()).isEqualTo("/original-order");
        assertThat(received.getBody().readUtf8()).isEqualTo(form
                ? "value=a%2Bb%26c%3D1" : "{\"value\":\"a+b&c=1\"}");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void shouldRejectRequestOutsidePostContract() {
        Request request = new Request.Builder().url(server.url("/original-order")).get().build();

        assertThatThrownBy(() -> factory.newCall(request, SingleStepTimeoutContract.initial(),
                deadlineAfterMillis(60_000))).isInstanceOf(IllegalArgumentException.class);

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void shouldStopContinuousBodyAtWholeCallBudget() {
        // 每200ms到一个字节，不触发400ms读超时；完整响应仍超过900ms总预算。
        server.enqueue(new MockResponse().setBody("0123456789")
                .throttleBody(1, 200, TimeUnit.MILLISECONDS));
        SingleStepTimeoutContract fixture = new SingleStepTimeoutContract(300, 400, 900, 3_000);
        Request request = new Request.Builder().url(server.url("/original-order"))
                .post(RequestBody.create("", MediaType.get("application/octet-stream"))).build();

        Throwable failure = catchThrowable(() -> {
            try (Response response = factory.newCall(request, fixture,
                    deadlineAfterMillis(3_000)).execute()) {
                response.body().string();
            }
        });

        assertThat(failure).isInstanceOf(IOException.class);
        assertThat(server.getRequestCount()).as("异常必须发生在实际请求之后：%s", failure).isEqualTo(1);
    }

    @Test
    void shouldClipCallToRemainingExecutionQualification() {
        // 600ms响应早于800ms读超时和1000ms总预算，但晚于剩余400ms资格。
        server.enqueue(new MockResponse().setHeadersDelay(600, TimeUnit.MILLISECONDS).setBody("late"));
        SingleStepTimeoutContract fixture = new SingleStepTimeoutContract(300, 800, 1_000, 3_000);

        assertThatThrownBy(() -> {
            try (Response response = factory.newCall(postRequest(), fixture,
                    deadlineAfterMillis(400)).execute()) {
                response.body().string();
            }
        }).isInstanceOf(IOException.class);

        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void shouldNotSendWithAlreadyExpiredQualification() {
        server.enqueue(new MockResponse().setBody("must-not-send"));

        assertThatThrownBy(() -> factory.newCall(postRequest(), SingleStepTimeoutContract.initial(),
                System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(1)))
                .isInstanceOf(InterruptedIOException.class);

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void shouldNotRestartBudgetWhenExecuteIsDelayed() throws Exception {
        server.enqueue(new MockResponse().setBody("must-not-send"));
        SingleStepTimeoutContract fixture = new SingleStepTimeoutContract(25, 50, 100, 1_000);
        Call call = factory.newCall(postRequest(), fixture, deadlineAfterMillis(1_000));
        // 故意让Call在交给执行者前耗尽固定预算；这不是等待异步结果的同步手段。
        TimeUnit.MILLISECONDS.sleep(150);

        assertThatThrownBy(() -> {
            try (Response response = call.execute()) {
                response.body().string();
            }
        }).isInstanceOf(InterruptedIOException.class);

        assertThat(server.getRequestCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 307, 408, 503})
    void shouldNotFollowRedirectOrRetryHttpResponse(int status) throws Exception {
        server.enqueue(new MockResponse().setResponseCode(status)
                .addHeader("Location", server.url("/must-not-follow"))
                .addHeader("Retry-After", "0").setBody("first-response"));
        server.enqueue(new MockResponse().setBody("must-not-retry"));

        try (Response response = factory.newCall(postRequest(), SingleStepTimeoutContract.initial(),
                deadlineAfterMillis(60_000)).execute()) {
            assertThat(response.code()).isEqualTo(status);
            assertThat(response.header("Retry-After")).isEqualTo("0");
            assertThat(response.body().string()).isEqualTo("first-response");
        }

        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void shouldNotReplayAfterRequestWasReceivedAndConnectionLost() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
        server.enqueue(new MockResponse().setBody("must-not-retry"));

        assertThatThrownBy(() -> {
            try (Response response = factory.newCall(postRequest(), SingleStepTimeoutContract.initial(),
                    deadlineAfterMillis(60_000)).execute()) {
                response.body().string();
            }
        }).isInstanceOf(IOException.class);

        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    private Request postRequest() {
        return new Request.Builder().url(server.url("/original-order"))
                .post(new FormBody.Builder().add("billno", "test-original-order").build()).build();
    }

    private static long deadlineAfterMillis(long millis) {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
    }
}
