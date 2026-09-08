package com.aifuli.module.supplychain.api.instore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.ToString;

import java.io.Serializable;

/**
 * 到店购入口地址响应，不暴露入口凭证或内部身份信息。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#41-api-层}。</p>
 */
@Schema(description = "到店购入口地址响应")
@Data
public class InstoreEntryUrlRespDTO implements Serializable {

    /** 入口地址可能携带短期凭证，禁止经 {@code toString()} 写入日志。详见 {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#41-api-层}。 */
    @Schema(description = "到店购入口地址", example = "https://example.com/instore/entry")
    @ToString.Exclude
    private String entryUrl;
}
