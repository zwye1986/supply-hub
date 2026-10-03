package com.aifuli.module.supplychain.supplieradapter.metadata;

import java.util.List;

/**
 * 适配器映射模板（封闭记录）：状态映射与错误码映射的协议知识单一来源。
 *
 * <p>UI『从模板初始化』（G2）据此为某连接器版本批量生成映射行，运营可在初始化后追加单条。
 * 模板字段与 {@code sc_status_mapping_definition} / {@code sc_supplier_error_code_map} 的协议列对齐，
 * 不含运行期指派列（id/connectorVersionId/mappingVersion/publishedAt/createdBy/createdAt/status/priority）。
 * 设计见
 * {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#51-adaptermetadata-扩展}。</p>
 *
 * @param statusMappings    状态映射模板，列表顺序即初始化优先级
 * @param errorCodeMappings 错误码映射模板，QNM 一期留空由运营按需追加
 */
public record MappingTemplates(
        List<StatusMappingTemplate> statusMappings,
        List<ErrorCodeMappingTemplate> errorCodeMappings) {

    /**
     * 单条状态映射模板，对应 {@code sc_status_mapping_definition} 协议列。
     *
     * @param externalStatus    供应商外部状态原始串（如 {@code orderStatus=1}）
     * @param externalSubStatus 外部子状态，无则空串
     * @param businessPhase     业务阶段（CREATE/QUERY/CALLBACK）
     * @param factType          映射后的平台事实类型（ACCEPTED/SUCCEEDED/REJECTED）
     * @param terminal          是否终态
     */
    public record StatusMappingTemplate(
            String externalStatus,
            String externalSubStatus,
            String businessPhase,
            String factType,
            boolean terminal) {
    }

    /**
     * 单条错误码映射模板，对应 {@code sc_supplier_error_code_map} 协议列。
     *
     * @param externalErrorCode 供应商外部错误码
     * @param externalSubCode   外部子错误码，无则空串
     * @param errorClass        错误分类（BUSINESS_REJECTED/RETRYABLE/RESULT_UNKNOWN/MANUAL_REQUIRED）
     * @param actionType        处置动作（REJECT/RETRY_SAFE/QUERY_FIRST/MANUAL）
     * @param retryable         是否可安全重试，须与 actionType=RETRY_SAFE 一致
     */
    public record ErrorCodeMappingTemplate(
            String externalErrorCode,
            String externalSubCode,
            String errorClass,
            String actionType,
            boolean retryable) {
    }
}
