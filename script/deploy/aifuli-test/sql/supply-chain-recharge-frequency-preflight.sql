-- 只读存量结构预检。执行前按部署清单停止受理、新旧SEND与Timer，统一目标制品和专用密钥。
-- 本脚本不证明真实手机号+SPU身份，也不解密或展示手机号、密文、凭证、buyerUserId。
-- 最终候选覆盖/冲突由受控Java离线入口恢复原AAD、专用HMAC与SKU冻结SPU后判断。
-- 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#101-迁移执行与核实边界
START TRANSACTION READ ONLY;
SELECT o.order_no, r.id request_id, r.status request_status, o.order_status,
       r.platform_sku_id, o.acceptance_receipt_id, e.id frozen_entry_id,
       CASE WHEN o.id IS NULL OR o.buyer_id <> r.buyer_id OR o.platform_sku_id <> r.platform_sku_id THEN 'ASSOCIATION_INVALID'
            WHEN s.id IS NULL OR s.spu_id IS NULL THEN 'PRODUCT_UNRECOVERABLE'
            WHEN r.target_ciphertext IS NULL OR OCTET_LENGTH(r.target_ciphertext)=0
                 OR r.target_key_version IS NULL THEN 'TARGET_UNRECOVERABLE'
            WHEN r.target_schema_code IS NULL OR r.target_schema_version IS NULL THEN 'TARGET_SCHEMA_INVALID'
            WHEN o.acceptance_receipt_id IS NOT NULL AND (a.id IS NULL OR a.result_order_id <> o.id
                 OR a.buyer_id <> o.buyer_id OR a.buyer_order_no <> o.buyer_order_no
                 OR a.accepted_at IS NULL) THEN 'ACCEPTANCE_TIME_INVALID'
            WHEN o.acceptance_receipt_id IS NULL AND o.created_at IS NULL THEN 'ACCEPTANCE_TIME_INVALID'
            ELSE 'JAVA_TRUSTED_IDENTITY_PREFLIGHT_REQUIRED' END structural_result
FROM sc_fulfillment_request r
LEFT JOIN sc_supply_chain_order o ON o.id=r.order_id
LEFT JOIN sc_platform_sku s ON s.id=r.platform_sku_id
LEFT JOIN sc_fulfillment_acceptance_receipt a ON a.id=o.acceptance_receipt_id
LEFT JOIN sc_recharge_frequency_entry e ON e.order_id=r.order_id
WHERE e.id IS NULL
ORDER BY o.id,r.id;

-- 仅按订单展示发送风险；不按历史展示手机号分组或猜测队列组合。
SELECT o.order_no, bo.id b_order_id, op.operation_no, op.status operation_status,
       op.result_class, op.attempt_count,
       CASE WHEN op.status IN (0,10) AND op.attempt_count=0 AND op.result_class IS NULL
                 AND op.external_operation_no IS NULL AND op.completed_at IS NULL
                 AND NOT EXISTS (SELECT 1 FROM sc_fulfillment_call_log c WHERE c.operation_id=op.id)
                 AND NOT EXISTS (SELECT 1 FROM sc_raw_status_observation obs WHERE obs.operation_id=op.id)
            THEN 'UNSENT_REQUIRES_FULL_JAVA_ASSOCIATION_CHECK'
            WHEN op.status=40 AND op.result_class='SUCCEEDED' THEN 'SUCCESS_REQUIRES_REQUEST_ORDER_CONSISTENCY'
            WHEN op.status=50 AND op.result_class='REJECTED' THEN 'REJECTION_REQUIRES_REQUEST_ORDER_CONSISTENCY'
            ELSE 'SEND_POSSIBLE_REQUIRES_TRUSTED_VERIFICATION' END operation_risk
FROM sc_fulfillment_operation op
JOIN sc_b_order bo ON bo.id=op.b_order_id
LEFT JOIN sc_supply_chain_order o ON o.id=bo.order_id
WHERE op.operation_kind='FULFILL' AND op.adapter_step_code IN ('SEND','SEND_KM')
ORDER BY bo.order_id,bo.id,op.id;
COMMIT;
