package com.aifuli.module.supplychain.integration.rtmart;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/**
 * 旅通适配器的统一安全异常因果边界。
 *
 * <p>安全副本仅保留边界类别、原异常 Java 类型名、栈帧以及有界的 cause/suppressed 结构；
 * 不保留原 message 或原 {@link Throwable} 引用，防止 token、jumpUrl、响应正文和配置原文进入最终异常树。
 * 设计见
 * {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#52-失败分类}。</p>
 */
final class RtmartSafeCause extends RuntimeException {

    /** 包含根节点在内最多递归的边数，防止恶意超深异常树占用无界内存。 */
    static final int MAX_SAFE_DEPTH = 12;

    /** 单节点最多复制的原 suppressed 数；超限时额外附加一个截断标记。 */
    static final int MAX_SAFE_SUPPRESSED = 16;

    /** 整棵安全异常树最多分配的节点数，不含共享的节点预算耗尽标记。 */
    static final int MAX_SAFE_NODES = 64;

    /** 单节点最多保留的原异常栈帧数。 */
    static final int MAX_SAFE_STACK_FRAMES_PER_NODE = 64;

    /** 整棵安全异常树最多保留的原异常栈帧总数。 */
    static final int MAX_SAFE_STACK_FRAMES_TOTAL = 512;

    /** suppressed 宽度截断标记的稳定、非敏感类型值。 */
    static final String SUPPRESSED_LIMIT_MARKER_TYPE = "TRUNCATED_SUPPRESSED";

    /** 整棵树节点预算耗尽时复用的稳定、非敏感标记类型。 */
    static final String NODE_LIMIT_MARKER_TYPE = "TRUNCATED_NODE_BUDGET";

    /** 原异常已在共享子图中出现时使用的稳定、非敏感标记类型。 */
    static final String REFERENCE_MARKER_TYPE = "REPEATED_THROWABLE_REFERENCE";

    private static final String CAUSE_SEGMENT = ".cause";
    private static final String SUPPRESSED_SEGMENT = ".suppressed";
    private static final String SUPPRESSED_LIMIT_SEGMENT = ".suppressed-limit";
    private static final String CYCLE_SEGMENT = ".cycle";
    private static final String REFERENCE_SEGMENT = ".reference";
    private static final String DEPTH_LIMIT_SEGMENT = ".depth-limit";
    private static final String STACK_LIMIT_SEGMENT = ".stack-limit";
    private static final String NODE_LIMIT_SEGMENT = ".node-limit";
    private static final StackTraceElement[] EMPTY_STACK = new StackTraceElement[0];
    private static final RtmartSafeCause NODE_LIMIT_MARKER = new RtmartSafeCause(
            "global" + NODE_LIMIT_SEGMENT, NODE_LIMIT_MARKER_TYPE, EMPTY_STACK, null,
            new RtmartSafeCause[0], false);

    private final String category;
    private final String originalType;

    private RtmartSafeCause(String category, String originalType, StackTraceElement[] originalStack,
                            RtmartSafeCause safeCause, RtmartSafeCause[] safeSuppressed) {
        this(category, originalType, originalStack, safeCause, safeSuppressed, true);
    }

    private RtmartSafeCause(String category, String originalType, StackTraceElement[] originalStack,
                            RtmartSafeCause safeCause, RtmartSafeCause[] safeSuppressed, boolean writableStack) {
        super("Rtmart safe cause [" + category + "]: " + originalType, safeCause, true, writableStack);
        this.category = category;
        this.originalType = originalType;
        if (writableStack) {
            setStackTrace(originalStack);
        }
        for (RtmartSafeCause suppressed : safeSuppressed) {
            addSuppressed(suppressed);
        }
    }

    /** @return 调用边界及 cause/suppressed/截断路径类别。 */
    String getCategory() {
        return category;
    }

    /** @return 原异常 Java 类型名；不保留原异常对象。 */
    String getOriginalType() {
        return originalType;
    }

    /**
     * 将任意异常树转换为安全副本。
     *
     * <p>转换以整棵树共享的对象身份表识别回环和重复引用，并同时限制递归深度、节点总数、栈帧总数及
     * 单节点 suppressed 宽度；任一预算超限均由安全标记节点显式表示，不静默丢弃。设计见
     * {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#52-失败分类}。</p>
     *
     * @param category 只能由适配器内部提供的非敏感边界类别
     * @param original 可能携带敏感 message 的原异常
     * @return 不持有原异常引用的安全副本；原异常为 {@code null} 时返回 {@code null}
     */
    static RtmartSafeCause from(String category, Throwable original) {
        if (original == null) {
            return null;
        }
        String safeCategory = Objects.requireNonNull(category, "category");
        return new CopyContext().copy(safeCategory, original, 0);
    }

    /**
     * 单次转换的全局预算与原异常身份表；原对象只作为 IdentityHashMap key 存活于当前调用栈，
     * 不写入任何返回节点。设计见
     * {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#52-失败分类}。
     */
    private static final class CopyContext {

        private final IdentityHashMap<Throwable, Boolean> visited = new IdentityHashMap<>();
        private final IdentityHashMap<Throwable, Boolean> activePath = new IdentityHashMap<>();
        private int remainingNodes = MAX_SAFE_NODES;
        private int remainingStackFrames = MAX_SAFE_STACK_FRAMES_TOTAL;

        private RtmartSafeCause copy(String category, Throwable original, int depth) {
            String originalType = original.getClass().getName();
            if (activePath.containsKey(original)) {
                return marker(category + CYCLE_SEGMENT, REFERENCE_MARKER_TYPE);
            }
            if (visited.put(original, Boolean.TRUE) != null) {
                return marker(category + REFERENCE_SEGMENT, REFERENCE_MARKER_TYPE);
            }
            if (depth >= MAX_SAFE_DEPTH) {
                return marker(category + DEPTH_LIMIT_SEGMENT, originalType);
            }
            if (!reserveNode()) {
                return NODE_LIMIT_MARKER;
            }

            StackCopy stackCopy = copyStack(original);
            String safeCategory = stackCopy.totalBudgetTruncated() ? category + STACK_LIMIT_SEGMENT : category;
            activePath.put(original, Boolean.TRUE);
            try {
                RtmartSafeCause safeCause = original.getCause() == null ? null
                        : copy(safeCategory + CAUSE_SEGMENT, original.getCause(), depth + 1);
                Throwable[] originalSuppressed = original.getSuppressed();
                int retainedSuppressed = Math.min(originalSuppressed.length, MAX_SAFE_SUPPRESSED);
                boolean suppressedTruncated = originalSuppressed.length > MAX_SAFE_SUPPRESSED;
                List<RtmartSafeCause> safeSuppressed = new ArrayList<>(
                        retainedSuppressed + (suppressedTruncated ? 1 : 0));
                for (int index = 0; index < retainedSuppressed; index++) {
                    if (remainingNodes == 0) {
                        safeSuppressed.add(NODE_LIMIT_MARKER);
                        break;
                    }
                    safeSuppressed.add(copy(safeCategory + SUPPRESSED_SEGMENT,
                            originalSuppressed[index], depth + 1));
                }
                if (suppressedTruncated && remainingNodes > 0) {
                    safeSuppressed.add(marker(safeCategory + SUPPRESSED_LIMIT_SEGMENT,
                            SUPPRESSED_LIMIT_MARKER_TYPE));
                }
                return new RtmartSafeCause(safeCategory, originalType, stackCopy.frames(), safeCause,
                        safeSuppressed.toArray(RtmartSafeCause[]::new));
            } finally {
                activePath.remove(original);
            }
        }

        private RtmartSafeCause marker(String category, String originalType) {
            if (!reserveNode()) {
                return NODE_LIMIT_MARKER;
            }
            return new RtmartSafeCause(category, originalType, EMPTY_STACK, null, new RtmartSafeCause[0]);
        }

        private boolean reserveNode() {
            if (remainingNodes == 0) {
                return false;
            }
            remainingNodes--;
            return true;
        }

        private StackCopy copyStack(Throwable original) {
            StackTraceElement[] originalStack = original.getStackTrace();
            int perNodeFrames = Math.min(originalStack.length, MAX_SAFE_STACK_FRAMES_PER_NODE);
            int retainedFrames = Math.min(perNodeFrames, remainingStackFrames);
            remainingStackFrames -= retainedFrames;
            return new StackCopy(Arrays.copyOf(originalStack, retainedFrames), retainedFrames < perNodeFrames);
        }
    }

    private record StackCopy(StackTraceElement[] frames, boolean totalBudgetTruncated) {
    }
}
