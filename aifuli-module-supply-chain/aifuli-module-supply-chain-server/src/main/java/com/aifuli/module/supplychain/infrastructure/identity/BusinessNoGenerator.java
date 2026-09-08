package com.aifuli.module.supplychain.infrastructure.identity;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 为外部可见业务对象生成“受控前缀 + ULID”的稳定编号。 */
@Component
@RequiredArgsConstructor
public class BusinessNoGenerator {

    private final UlidGenerator ulidGenerator;

    public String next(String prefix) {
        if (!BusinessNoPrefixes.ALL.contains(prefix)) {
            throw new IllegalArgumentException("Unsupported business number prefix: " + prefix);
        }
        String businessNo = prefix + ulidGenerator.next();
        if (businessNo.length() > 32) {
            throw new IllegalStateException("Business number exceeds DDL length: " + prefix);
        }
        return businessNo;
    }
}
