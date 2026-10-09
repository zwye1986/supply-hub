package com.aifuli.module.supplychain.routing.application;

import java.util.Objects;
import java.time.LocalDateTime;

/**
 * 当前供给的封闭可恢复缺条件；不得用于指针损坏、归属矛盾、SQL 或 JSON 故障。
 * Reader 只捕获此类型，不泛捕获 IllegalStateException。
 * 见 docs/superpowers/specs/2026-10-09-saleable-catalog-order-acceptance-design.md#71-资格语义不变。
 */
public final class SupplyQualificationUnavailableException extends IllegalStateException {
    public enum Reason {
        NO_ACTIVE_POLICY,
        NO_EFFECTIVE_POLICY_VERSION,
        NO_PUBLISHED_RULE,
        NO_MATCHED_RULE,
        NO_DYNAMIC_CANDIDATE,
        SUBJECT_NOT_ACTIVE_OR_EXACT
    }

    private final Reason reason;
    private final LocalDateTime nextFactBoundary;

    public SupplyQualificationUnavailableException(Reason reason, String description) {
        this(reason, description, null);
    }

    public SupplyQualificationUnavailableException(Reason reason, String description, LocalDateTime nextFactBoundary) {
        super(Objects.requireNonNull(reason).name() + ": " + description);
        this.reason = reason;
        this.nextFactBoundary = nextFactBoundary;
    }

    public Reason reason() {
        return reason;
    }

    public LocalDateTime nextFactBoundary() {
        return nextFactBoundary;
    }
}
