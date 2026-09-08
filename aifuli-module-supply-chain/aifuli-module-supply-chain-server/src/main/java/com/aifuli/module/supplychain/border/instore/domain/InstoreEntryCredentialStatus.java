package com.aifuli.module.supplychain.border.instore.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 到店购入口凭证状态；PROCESSING 表示已有唯一请求正在查询会员资料，
 * REVOKED 为撤销终态，仅由入口 URL 调用失败、冻结档案退役或 018 迁移进入。
 *
 * <p>设计见 {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#8-凭证状态机}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum InstoreEntryCredentialStatus {
    ACTIVE((short) 10),
    PROCESSING((short) 15),
    USED((short) 20),
    EXPIRED((short) 30),
    REVOKED((short) 40);

    private final short code;

    /**
     * 按持久化码解析状态；未知码直接拒绝，避免把脏数据猜成业务状态。
     */
    public static InstoreEntryCredentialStatus fromCode(short code) {
        for (InstoreEntryCredentialStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown instore entry credential status: " + code);
    }
}
