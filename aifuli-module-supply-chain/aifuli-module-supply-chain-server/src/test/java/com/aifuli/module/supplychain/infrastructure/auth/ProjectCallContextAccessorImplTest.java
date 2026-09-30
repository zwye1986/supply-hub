package com.aifuli.module.supplychain.infrastructure.auth;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 可信上下文桥接单测：认证快照、ThreadLocal 隔离和三型 scope 映射全部失败关闭。
 *
 * <p>buyerUserId 始终按不透明字符串原样传递，禁止转为 Long。详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#65-projectauthcontextholder-与-projectcallcontextaccessorimpl}。</p>
 */
class ProjectCallContextAccessorImplTest {

    private static final String APP_ID_SENTINEL = "APP-ID-SENTINEL";
    private static final String BUYER_USER_SENTINEL = "000123456789012345678901234567890";
    private static final String CHANNEL_SENTINEL = "CHANNEL-CODE-SENTINEL";

    private final ProjectCallContextAccessor accessor = new ProjectCallContextAccessorImpl();

    @AfterEach
    void cleanUp() {
        ProjectAuthContextHolder.clear();
    }

    @Test
    void contextAcceptsAndCanonicalizesAllRegisteredScopeShapes() {
        ProjectAuthContext buyer = buyerContext("   ");
        ProjectAuthContext channel = channelContext("");
        ProjectAuthContext project = projectContext(null, "\t");

        assertThat(buyer.buyerUserId()).isEqualTo(BUYER_USER_SENTINEL);
        assertThat(buyer.channelCode()).isNull();
        assertThat(channel.buyerUserId()).isNull();
        assertThat(channel.channelCode()).isEqualTo(CHANNEL_SENTINEL);
        assertThat(project.buyerUserId()).isNull();
        assertThat(project.channelCode()).isNull();
    }

    @Test
    void trustedSnapshotAndHolderArePackagePrivateAndSnapshotCachesScope() throws NoSuchFieldException {
        assertThat(ProjectAuthContext.class.isRecord()).isFalse();
        assertThat(Modifier.isPublic(ProjectAuthContext.class.getModifiers())).isFalse();
        assertThat(Modifier.isFinal(ProjectAuthContext.class.getModifiers())).isTrue();
        assertThat(Arrays.stream(ProjectAuthContext.class.getDeclaredConstructors())
                .map(Constructor::getModifiers)
                .noneMatch(Modifier::isPublic)).isTrue();

        Set<String> accessorNames = Set.of("projectId", "buyerId", "appId", "keyVersion",
                "operationCode", "buyerUserId", "channelCode", "scopeType");
        assertThat(Arrays.stream(ProjectAuthContext.class.getDeclaredMethods())
                .filter(method -> accessorNames.contains(method.getName()))
                .map(Method::getModifiers)
                .noneMatch(Modifier::isPublic)).isTrue();
        assertThat(Arrays.stream(ProjectAuthContext.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getModifiers)
                .allMatch(Modifier::isFinal)).isTrue();
        Field cachedScope = ProjectAuthContext.class.getDeclaredField("scopeType");
        assertThat(Modifier.isFinal(cachedScope.getModifiers())).isTrue();

        assertThat(Modifier.isPublic(ProjectAuthContextHolder.class.getModifiers())).isFalse();
        Set<String> holderMethods = Arrays.stream(ProjectAuthContextHolder.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());
        assertThat(holderMethods).contains("set", "get", "clear");
        assertThat(Arrays.stream(ProjectAuthContextHolder.class.getDeclaredMethods())
                .filter(method -> Set.of("set", "get", "clear").contains(method.getName()))
                .map(Method::getModifiers)
                .noneMatch(Modifier::isPublic)).isTrue();
    }

    @Test
    void contextRejectsInvalidCoreIdentityFields() {
        assertInvalidContext(() -> new ProjectAuthContext(null, 2L, APP_ID_SENTINEL, 1,
                "INSTORE_HOME_ENTRY", BUYER_USER_SENTINEL, null));
        assertInvalidContext(() -> new ProjectAuthContext(0L, 2L, APP_ID_SENTINEL, 1,
                "INSTORE_HOME_ENTRY", BUYER_USER_SENTINEL, null));
        assertInvalidContext(() -> new ProjectAuthContext(1L, null, APP_ID_SENTINEL, 1,
                "INSTORE_HOME_ENTRY", BUYER_USER_SENTINEL, null));
        assertInvalidContext(() -> new ProjectAuthContext(1L, -2L, APP_ID_SENTINEL, 1,
                "INSTORE_HOME_ENTRY", BUYER_USER_SENTINEL, null));
        assertInvalidContext(() -> new ProjectAuthContext(1L, 2L, null, 1,
                "INSTORE_HOME_ENTRY", BUYER_USER_SENTINEL, null));
        assertInvalidContext(() -> new ProjectAuthContext(1L, 2L, "  ", 1,
                "INSTORE_HOME_ENTRY", BUYER_USER_SENTINEL, null));
        assertInvalidContext(() -> new ProjectAuthContext(1L, 2L, APP_ID_SENTINEL, 0,
                "INSTORE_HOME_ENTRY", BUYER_USER_SENTINEL, null));
        assertInvalidContext(() -> new ProjectAuthContext(1L, 2L, APP_ID_SENTINEL, 1,
                null, BUYER_USER_SENTINEL, null));
        assertInvalidContext(() -> new ProjectAuthContext(1L, 2L, APP_ID_SENTINEL, 1,
                "  ", BUYER_USER_SENTINEL, null));
    }

    @Test
    void contextRejectsUnknownOperationWithoutEchoingIt() {
        String unknownOperation = "UNKNOWN-OPERATION-SENTINEL";

        assertThatThrownBy(() -> new ProjectAuthContext(1L, 2L, APP_ID_SENTINEL, 1,
                unknownOperation, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(unknownOperation)
                .hasMessageNotContaining(APP_ID_SENTINEL);
    }

    @Test
    void contextRejectsMissingOrExtraBuyerScopeWithoutLeakingValues() {
        assertSensitiveScopeRejected(() -> new ProjectAuthContext(1L, 2L, APP_ID_SENTINEL, 1,
                "INSTORE_HOME_ENTRY", null, null));
        assertSensitiveScopeRejected(() -> new ProjectAuthContext(1L, 2L, APP_ID_SENTINEL, 1,
                "INSTORE_HOME_ENTRY", BUYER_USER_SENTINEL, CHANNEL_SENTINEL));
    }

    @Test
    void contextRejectsMissingOrExtraChannelScopeWithoutLeakingValues() {
        assertSensitiveScopeRejected(() -> new ProjectAuthContext(1L, 2L, APP_ID_SENTINEL, 1,
                "INSTORE_RESOLVE_MEMBER", null, null));
        assertSensitiveScopeRejected(() -> new ProjectAuthContext(1L, 2L, APP_ID_SENTINEL, 1,
                "INSTORE_RESOLVE_MEMBER", BUYER_USER_SENTINEL, CHANNEL_SENTINEL));
    }

    @Test
    void contextRejectsDeclarationsForNoneScopeWithoutLeakingValues() {
        assertSensitiveScopeRejected(() -> projectContext(BUYER_USER_SENTINEL, null));
        assertSensitiveScopeRejected(() -> projectContext(null, CHANNEL_SENTINEL));
    }

    @Test
    void contextToStringOmitsApplicationAndScopeValues() {
        ProjectAuthContext context = buyerContext(null);

        assertThat(context.toString())
                .contains("projectId=1", "buyerId=2", "keyVersion=1", "INSTORE_HOME_ENTRY")
                .doesNotContain(APP_ID_SENTINEL, BUYER_USER_SENTINEL, CHANNEL_SENTINEL);
    }

    @Test
    void holderRejectsNullAndClearRemovesContext() {
        assertThatThrownBy(() -> ProjectAuthContextHolder.set(null))
                .isInstanceOf(NullPointerException.class);

        ProjectAuthContext context = buyerContext(null);
        ProjectAuthContextHolder.set(context);
        assertThat(ProjectAuthContextHolder.get()).isSameAs(context);

        ProjectAuthContextHolder.clear();
        assertThat(ProjectAuthContextHolder.get()).isNull();
    }

    @Test
    void holderReplacesContextInSameThread() {
        ProjectAuthContext first = buyerContext(null);
        ProjectAuthContext second = channelContext(null);

        ProjectAuthContextHolder.set(first);
        ProjectAuthContextHolder.set(second);

        assertThat(ProjectAuthContextHolder.get()).isSameAs(second);
    }

    @Test
    void holderDoesNotInheritAndKeepsChildChangesIsolated() throws InterruptedException {
        ProjectAuthContext parentContext = buyerContext(null);
        ProjectAuthContext childContext = channelContext(null);
        AtomicReference<ProjectAuthContext> inherited = new AtomicReference<>();
        AtomicReference<ProjectAuthContext> childOwned = new AtomicReference<>();
        AtomicReference<Throwable> childFailure = new AtomicReference<>();
        ProjectAuthContextHolder.set(parentContext);

        Thread child = new Thread(() -> {
            try {
                inherited.set(ProjectAuthContextHolder.get());
                ProjectAuthContextHolder.set(childContext);
                childOwned.set(ProjectAuthContextHolder.get());
            } catch (Throwable throwable) {
                childFailure.set(throwable);
            } finally {
                ProjectAuthContextHolder.clear();
            }
        }, "project-auth-context-isolation-test");
        child.start();
        child.join();

        assertThat(childFailure.get()).isNull();
        assertThat(inherited.get()).isNull();
        assertThat(childOwned.get()).isSameAs(childContext);
        assertThat(ProjectAuthContextHolder.get()).isSameAs(parentContext);
    }

    @Test
    void buyerScopeOnlyProducesBuyerContextAndPreservesOpaqueIdentifier() {
        ProjectAuthContextHolder.set(buyerContext(null));

        ProjectCallContextAccessor.BuyerContext context = accessor.requireBuyerContext();

        assertThat(context.projectId()).isEqualTo(1L);
        assertThat(context.buyerId()).isEqualTo(2L);
        assertThat(context.buyerUserId()).isEqualTo(BUYER_USER_SENTINEL);
        assertBuyerAuthInvalid(accessor::requireChannelContext);
        assertBuyerAuthInvalid(accessor::requireProjectContext);
    }

    @Test
    void channelScopeOnlyProducesChannelContext() {
        ProjectAuthContextHolder.set(channelContext(null));

        ProjectCallContextAccessor.ChannelContext context = accessor.requireChannelContext();

        assertThat(context.projectId()).isEqualTo(1L);
        assertThat(context.channelCode()).isEqualTo(CHANNEL_SENTINEL);
        assertBuyerAuthInvalid(accessor::requireBuyerContext);
        assertBuyerAuthInvalid(accessor::requireProjectContext);
    }

    @Test
    void noneScopeOnlyProducesProjectContext() {
        ProjectAuthContextHolder.set(projectContext(null, null));

        assertThat(accessor.requireProjectContext().projectId()).isEqualTo(1L);
        assertBuyerAuthInvalid(accessor::requireBuyerContext);
        assertBuyerAuthInvalid(accessor::requireChannelContext);
    }

    @Test
    void allAccessorsFailClosedWithoutAuthenticatedContext() {
        assertBuyerAuthInvalid(accessor::requireBuyerContext);
        assertBuyerAuthInvalid(accessor::requireChannelContext);
        assertBuyerAuthInvalid(accessor::requireProjectContext);
    }

    private static ProjectAuthContext buyerContext(String unusedChannelCode) {
        return new ProjectAuthContext(1L, 2L, APP_ID_SENTINEL, 1,
                "INSTORE_HOME_ENTRY", BUYER_USER_SENTINEL, unusedChannelCode);
    }

    private static ProjectAuthContext channelContext(String unusedBuyerUserId) {
        return new ProjectAuthContext(1L, 2L, APP_ID_SENTINEL, 1,
                "INSTORE_RESOLVE_MEMBER", unusedBuyerUserId, CHANNEL_SENTINEL);
    }

    private static ProjectAuthContext projectContext(String buyerUserId, String channelCode) {
        return new ProjectAuthContext(1L, 2L, APP_ID_SENTINEL, 1,
                "INSTORE_CONFIRM_PAYMENT_CALLBACK", buyerUserId, channelCode);
    }

    private static void assertInvalidContext(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(APP_ID_SENTINEL)
                .hasMessageNotContaining(BUYER_USER_SENTINEL)
                .hasMessageNotContaining(CHANNEL_SENTINEL);
    }

    private static void assertSensitiveScopeRejected(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertInvalidContext(action);
    }

    private static void assertBuyerAuthInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(ServiceException.class)
                .satisfies(error -> {
                    ServiceException serviceException = (ServiceException) error;
                    assertThat(serviceException.getCode()).isEqualTo(BUYER_AUTH_INVALID.getCode());
                    assertThat(serviceException.getMessage())
                            .doesNotContain(APP_ID_SENTINEL, BUYER_USER_SENTINEL, CHANNEL_SENTINEL);
                });
    }
}
