package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.RechargeFrequencyActionAuditDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyActionAuditMapper;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/**
 * 全局actionNo追加审计。指纹仅在内存处理完整原始命令，原因持久化前脱敏，不复用手机号身份密钥。
 * 成功插入加入调用方供应链事务；拒绝审计与冲突回放由命令编排在回滚后另开新事务。
 * 设计见 {@code docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#7-管理端契约与权限}。
 */
@Service @RequiredArgsConstructor
public class RechargeFrequencyActionAuditService {
    private static final Pattern MOBILE = Pattern.compile("(?:\\+?86[-\\s]*)?1[3-9](?:[-\\s]*[0-9]){9}");
    private final RechargeFrequencyActionAuditMapper mapper;
    private final SupplyChainDatabaseClock clock;
    private final ObjectMapper objectMapper;

    /** 只在命令编排新事务读取；失败事务退出前禁止调用回放。 */
    @Transactional(transactionManager="supplyChainTransactionManager", propagation=Propagation.MANDATORY, readOnly=true)
    public RechargeFrequencyActionAuditDO find(String actionNo) { return mapper.selectByActionNo(actionNo); }

    @Transactional(transactionManager="supplyChainTransactionManager", propagation=Propagation.MANDATORY, rollbackFor=Exception.class)
    public void append(RechargeFrequencyActionAuditDO audit) {
        audit.setReason(maskReason(audit.getReason()));
        audit.setCreatedAt(clock.now());
        if (mapper.insert(audit) != 1) { throw new IllegalStateException("限频操作审计未持久化"); }
    }
    /** SHA-256规范化JSON数组覆盖真实actor及完整原参数，不存原始原因。 */
    public byte[] fingerprint(Object[] canonicalFields) {
        try { return MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(canonicalFields)); }
        catch (NoSuchAlgorithmException | JsonProcessingException cause) { throw new IllegalStateException("限频操作指纹生成失败", cause); }
    }
    /** 调用方只提供显式安全白名单字段，禁止传DO或target正文。 */
    public String snapshot(Object safeFields) {
        try { return objectMapper.writeValueAsString(safeFields); }
        catch (JsonProcessingException cause) { throw new IllegalStateException("限频安全快照生成失败", cause); }
    }
    /** 原因可嵌入带国家码、空格或连字符的手机号；只持久化脱敏值，指纹仍覆盖原文。 */
    static String maskReason(String reason) {
        var matcher=MOBILE.matcher(reason); var result=new StringBuilder();
        while (matcher.find()) {
            String digits=matcher.group().replaceAll("[^0-9]", "");
            String mobile=digits.substring(digits.length()-11);
            matcher.appendReplacement(result, mobile.substring(0,3)+"****"+mobile.substring(7));
        }
        matcher.appendTail(result); return result.toString();
    }
}
