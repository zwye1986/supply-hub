-- 供应链数据库时间语义从 UTC 墙上时间原地迁移为 Asia/Shanghai 本地墙上时间。
-- 设计依据：docs/superpowers/specs/2026-09-04-supply-chain-database-local-time-design.md#4-数据库迁移设计
--
-- 迁移前置条件：001～021 已执行，且既有 DATETIME(3) 数据均按 UTC 墙上时间写入。
-- Liquibase 仅执行本 changeSet 一次；严禁脱离 DATABASECHANGELOG 重复手工执行本文件，
-- 否则既有时间会被重复增加 8 小时。

-- 1. 先执行可重复的列定义变更，避免 MySQL DDL 隐式提交后发生数据二次偏移。
ALTER TABLE `sc_account_ledger`
    MODIFY COLUMN `occurred_at` DATETIME(3) NOT NULL
    COMMENT '资金事实发生时间，Asia/Shanghai';
ALTER TABLE `sc_account_ledger`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '流水入库时间，Asia/Shanghai';
ALTER TABLE `sc_b_order`
    MODIFY COLUMN `started_at` DATETIME(3) NULL
    COMMENT '首次开始外部履约时间，Asia/Shanghai';
ALTER TABLE `sc_b_order`
    MODIFY COLUMN `completed_at` DATETIME(3) NULL
    COMMENT '履约尝试进入终态时间，Asia/Shanghai';
ALTER TABLE `sc_b_order`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT 'B端履约尝试创建时间，Asia/Shanghai';
ALTER TABLE `sc_b_order`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT 'B端履约尝试最后更新时间，Asia/Shanghai';
ALTER TABLE `sc_brand`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_brand`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_brand_alias`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_brand_alias`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_buyer`
    MODIFY COLUMN `cooperation_started_at` DATETIME(3) NULL
    COMMENT '合作开始时间，Asia/Shanghai';
ALTER TABLE `sc_buyer`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_buyer`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_access_client`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_access_client`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_account`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_account`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_catalog_change`
    MODIFY COLUMN `occurred_at` DATETIME(3) NOT NULL
    COMMENT '变更发生时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_category_scope`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_contact`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_contact`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_cooperation_mode`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_credential_set`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_credential_set`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_credential_version`
    MODIFY COLUMN `valid_from` DATETIME(3) NOT NULL
    COMMENT '凭证最早可使用时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_credential_version`
    MODIFY COLUMN `valid_to` DATETIME(3) NULL
    COMMENT '凭证最晚可验证时间，Asia/Shanghai，空表示未预设到期';
ALTER TABLE `sc_buyer_credential_version`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_credential_version`
    MODIFY COLUMN `activated_at` DATETIME(3) NULL
    COMMENT '凭证版本激活时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_credential_version`
    MODIFY COLUMN `retired_at` DATETIME(3) NULL
    COMMENT '凭证版本退役时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_file_ref`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_integration_call_log`
    MODIFY COLUMN `started_at` DATETIME(3) NOT NULL
    COMMENT '本次调用开始时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_integration_call_log`
    MODIFY COLUMN `completed_at` DATETIME(3) NULL
    COMMENT '本次调用结束时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_integration_capability`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_integration_mode`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_integration_profile`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_integration_profile`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_integration_profile_version`
    MODIFY COLUMN `effective_at` DATETIME(3) NULL
    COMMENT '版本业务生效时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_integration_profile_version`
    MODIFY COLUMN `validated_at` DATETIME(3) NULL
    COMMENT '配置验证通过时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_integration_profile_version`
    MODIFY COLUMN `published_at` DATETIME(3) NULL
    COMMENT '配置版本发布时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_integration_profile_version`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_integration_profile_version`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_listing`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_listing`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_listing_availability`
    MODIFY COLUMN `evaluated_at` DATETIME(3) NOT NULL
    COMMENT '可用性评估时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_listing_availability`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '投影最后更新时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_listing_version`
    MODIFY COLUMN `valid_from` DATETIME(3) NOT NULL
    COMMENT '版本业务生效时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_listing_version`
    MODIFY COLUMN `valid_to` DATETIME(3) NULL
    COMMENT '版本业务失效时间，Asia/Shanghai，空表示长期有效';
ALTER TABLE `sc_buyer_listing_version`
    MODIFY COLUMN `published_at` DATETIME(3) NOT NULL
    COMMENT '版本发布时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_listing_version`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '版本记录创建时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_product`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_product`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_product_l2_override`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_product_l2_override_version`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_product_settlement_price`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_product_settlement_price_version`
    MODIFY COLUMN `published_at` DATETIME(3) NOT NULL
    COMMENT '发布时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_product_settlement_price_version`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_project`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_project`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_tenant_binding`
    MODIFY COLUMN `valid_from` DATETIME(3) NOT NULL
    COMMENT '绑定生效时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_tenant_binding`
    MODIFY COLUMN `valid_to` DATETIME(3) NULL
    COMMENT '绑定失效时间，Asia/Shanghai，空表示未到期';
ALTER TABLE `sc_buyer_tenant_binding`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_buyer_tenant_binding`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_credential_slot`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_credential_slot`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_fulfillment_acceptance_receipt`
    MODIFY COLUMN `accepted_at` DATETIME(3) NULL
    COMMENT '受理成功时间，Asia/Shanghai';
ALTER TABLE `sc_fulfillment_acceptance_receipt`
    MODIFY COLUMN `rejected_at` DATETIME(3) NULL
    COMMENT '最近一次明确拒绝时间，Asia/Shanghai';
ALTER TABLE `sc_fulfillment_acceptance_receipt`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '受理回执创建时间，Asia/Shanghai';
ALTER TABLE `sc_fulfillment_acceptance_receipt`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '受理回执最后更新时间，Asia/Shanghai';
ALTER TABLE `sc_fulfillment_asset`
    MODIFY COLUMN `valid_from` DATETIME(3) NULL
    COMMENT '资产有效期开始时间，Asia/Shanghai';
ALTER TABLE `sc_fulfillment_asset`
    MODIFY COLUMN `valid_until` DATETIME(3) NULL
    COMMENT '资产有效期结束时间，Asia/Shanghai';
ALTER TABLE `sc_fulfillment_asset`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '资产创建时间，Asia/Shanghai';
ALTER TABLE `sc_fulfillment_asset`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '资产最后更新时间，Asia/Shanghai';
ALTER TABLE `sc_fulfillment_call_log`
    MODIFY COLUMN `started_at` DATETIME(3) NOT NULL
    COMMENT '调用开始时间，Asia/Shanghai';
ALTER TABLE `sc_fulfillment_call_log`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '调用审计入库时间，Asia/Shanghai';
ALTER TABLE `sc_fulfillment_exception_case`
    MODIFY COLUMN `resolved_at` DATETIME(3) NULL
    COMMENT '处置解决时间，Asia/Shanghai';
ALTER TABLE `sc_fulfillment_exception_case`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '异常处置单创建时间，Asia/Shanghai';
ALTER TABLE `sc_fulfillment_exception_case`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '异常处置单最后更新时间，Asia/Shanghai';
ALTER TABLE `sc_fulfillment_operation`
    MODIFY COLUMN `next_recover_at` DATETIME(3) NULL
    COMMENT '下一次查单或恢复时间，Asia/Shanghai';
ALTER TABLE `sc_fulfillment_operation`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '操作创建时间，Asia/Shanghai';
ALTER TABLE `sc_fulfillment_operation`
    MODIFY COLUMN `completed_at` DATETIME(3) NULL
    COMMENT '操作进入终态时间，Asia/Shanghai';
ALTER TABLE `sc_fulfillment_operation`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '操作最后更新时间，Asia/Shanghai';
ALTER TABLE `sc_fulfillment_request`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '履约请求创建时间，Asia/Shanghai';
ALTER TABLE `sc_fulfillment_request`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '履约请求最后更新时间，Asia/Shanghai';
ALTER TABLE `sc_fund_reservation`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '资金预留创建时间，Asia/Shanghai';
ALTER TABLE `sc_fund_reservation`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '资金预留最后更新时间，Asia/Shanghai';
ALTER TABLE `sc_inbox_event`
    MODIFY COLUMN `received_at` DATETIME(3) NOT NULL
    COMMENT '消费者首次收到事件时间，Asia/Shanghai';
ALTER TABLE `sc_inbox_event`
    MODIFY COLUMN `processed_at` DATETIME(3) NULL
    COMMENT '消费者成功完成业务事务时间，Asia/Shanghai';
ALTER TABLE `sc_inbox_event`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '消费记录最后更新时间，Asia/Shanghai';
ALTER TABLE `sc_instore_channel_order`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '渠道订单创建时间，Asia/Shanghai';
ALTER TABLE `sc_instore_channel_order`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '渠道订单最后更新时间，Asia/Shanghai';
ALTER TABLE `sc_instore_channel_order`
    MODIFY COLUMN `completed_at` DATETIME(3) NULL
    COMMENT '渠道订单进入业务终态时间，Asia/Shanghai';
ALTER TABLE `sc_instore_channel_user_binding`
    MODIFY COLUMN `token_expires_at` DATETIME(3) NULL
    COMMENT '013兼容token失效时间，Asia/Shanghai；生产路径禁止读写';
ALTER TABLE `sc_instore_channel_user_binding`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '绑定创建时间，Asia/Shanghai';
ALTER TABLE `sc_instore_channel_user_binding`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '绑定最后更新时间，Asia/Shanghai';
ALTER TABLE `sc_instore_entry_credential`
    MODIFY COLUMN `expires_at` DATETIME(3) NOT NULL
    COMMENT '凭证失效时间，Asia/Shanghai';
ALTER TABLE `sc_instore_entry_credential`
    MODIFY COLUMN `processing_started_at` DATETIME(3) NULL
    COMMENT '凭证被会员查询请求独占的时间，Asia/Shanghai';
ALTER TABLE `sc_instore_entry_credential`
    MODIFY COLUMN `used_at` DATETIME(3) NULL
    COMMENT '会员资料成功返回后的凭证消费时间，Asia/Shanghai';
ALTER TABLE `sc_instore_entry_credential`
    MODIFY COLUMN `revoked_at` DATETIME(3) NULL
    COMMENT '凭证撤销时间，Asia/Shanghai';
ALTER TABLE `sc_instore_entry_credential`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '凭证创建时间，Asia/Shanghai';
ALTER TABLE `sc_instore_entry_credential`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '凭证最后更新时间，Asia/Shanghai';
ALTER TABLE `sc_instore_operation`
    MODIFY COLUMN `next_recover_at` DATETIME(3) NULL
    COMMENT '下一次查单或恢复时间，Asia/Shanghai';
ALTER TABLE `sc_instore_operation`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '操作创建时间，Asia/Shanghai';
ALTER TABLE `sc_instore_operation`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '操作最后更新时间，Asia/Shanghai';
ALTER TABLE `sc_instore_operation`
    MODIFY COLUMN `completed_at` DATETIME(3) NULL
    COMMENT '操作进入终态时间，Asia/Shanghai';
ALTER TABLE `sc_instore_payment_flow`
    MODIFY COLUMN `pay_time` DATETIME(3) NULL
    COMMENT '支付成功时间，Asia/Shanghai，映射旅通接口③pay_time';
ALTER TABLE `sc_instore_payment_flow`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '支付流水创建时间，Asia/Shanghai';
ALTER TABLE `sc_instore_payment_flow`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '支付流水最后更新时间，Asia/Shanghai';
ALTER TABLE `sc_instore_payment_flow`
    MODIFY COLUMN `completed_at` DATETIME(3) NULL
    COMMENT '支付流水进入业务终态时间，Asia/Shanghai';
ALTER TABLE `sc_instore_refund_case`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '退款Case创建时间，Asia/Shanghai';
ALTER TABLE `sc_instore_refund_case`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '退款Case最后更新时间，Asia/Shanghai';
ALTER TABLE `sc_instore_refund_case`
    MODIFY COLUMN `completed_at` DATETIME(3) NULL
    COMMENT '退款Case完成时间，Asia/Shanghai';
ALTER TABLE `sc_instore_supplier_order`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '供应商订单创建时间，Asia/Shanghai';
ALTER TABLE `sc_instore_supplier_order`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '供应商订单最后更新时间，Asia/Shanghai';
ALTER TABLE `sc_instore_supplier_order`
    MODIFY COLUMN `completed_at` DATETIME(3) NULL
    COMMENT '供应商订单进入业务终态时间，Asia/Shanghai';
ALTER TABLE `sc_inventory_snapshot`
    MODIFY COLUMN `observed_at` DATETIME(3) NOT NULL
    COMMENT '供应商库存观察时间，Asia/Shanghai';
ALTER TABLE `sc_inventory_snapshot`
    MODIFY COLUMN `valid_until` DATETIME(3) NOT NULL
    COMMENT '库存观察有效截止时间，Asia/Shanghai';
ALTER TABLE `sc_inventory_snapshot`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '快照入库时间，Asia/Shanghai';
ALTER TABLE `sc_inventory_source`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_inventory_source`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_o2o_channel_detail`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT 'O2O详情创建时间，Asia/Shanghai';
ALTER TABLE `sc_o2o_channel_detail`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT 'O2O详情最后更新时间，Asia/Shanghai';
ALTER TABLE `sc_o2o_channel_user_binding`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '绑定创建时间，Asia/Shanghai';
ALTER TABLE `sc_o2o_channel_user_binding`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '绑定最后更新时间，Asia/Shanghai';
ALTER TABLE `sc_o2o_operation`
    MODIFY COLUMN `next_recover_at` DATETIME(3) NULL
    COMMENT '下一次查单或恢复时间，Asia/Shanghai';
ALTER TABLE `sc_o2o_operation`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT 'O2O操作创建时间，Asia/Shanghai';
ALTER TABLE `sc_o2o_operation`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT 'O2O操作最后更新时间，Asia/Shanghai';
ALTER TABLE `sc_o2o_operation`
    MODIFY COLUMN `completed_at` DATETIME(3) NULL
    COMMENT 'O2O操作进入终态时间，Asia/Shanghai';
ALTER TABLE `sc_o2o_refund_case`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '退款Case创建时间，Asia/Shanghai';
ALTER TABLE `sc_o2o_refund_case`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '退款Case最后更新时间，Asia/Shanghai';
ALTER TABLE `sc_o2o_refund_case`
    MODIFY COLUMN `completed_at` DATETIME(3) NULL
    COMMENT '退款Case完成时间，Asia/Shanghai';
ALTER TABLE `sc_o2o_supplier_order`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '供应商订单创建时间，Asia/Shanghai';
ALTER TABLE `sc_o2o_supplier_order`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '供应商订单最后更新时间，Asia/Shanghai';
ALTER TABLE `sc_o2o_supplier_order`
    MODIFY COLUMN `completed_at` DATETIME(3) NULL
    COMMENT '供应商订单进入业务终态时间，Asia/Shanghai';
ALTER TABLE `sc_order_status_log`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '状态审计创建时间，Asia/Shanghai';
ALTER TABLE `sc_outbox_event`
    MODIFY COLUMN `available_at` DATETIME(3) NOT NULL
    COMMENT '事件最早可投递时间，Asia/Shanghai';
ALTER TABLE `sc_outbox_event`
    MODIFY COLUMN `lease_until` DATETIME(3) NULL
    COMMENT '当前事件租约到期时间，Asia/Shanghai';
ALTER TABLE `sc_outbox_event`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '事件创建时间，Asia/Shanghai';
ALTER TABLE `sc_outbox_event`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '事件状态最后更新时间，Asia/Shanghai';
ALTER TABLE `sc_outbox_event`
    MODIFY COLUMN `published_at` DATETIME(3) NULL
    COMMENT '事件成功投递时间，Asia/Shanghai';
ALTER TABLE `sc_platform_settlement_price`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_platform_settlement_price_version`
    MODIFY COLUMN `published_at` DATETIME(3) NOT NULL
    COMMENT '发布时间，Asia/Shanghai';
ALTER TABLE `sc_platform_settlement_price_version`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_platform_sku`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_platform_sku`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_platform_spu`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_platform_spu`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_product_category`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_product_category`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_project_app_credential_version`
    MODIFY COLUMN `valid_from` DATETIME(3) NOT NULL
    COMMENT '密钥生效时间，Asia/Shanghai';
ALTER TABLE `sc_project_app_credential_version`
    MODIFY COLUMN `valid_to` DATETIME(3) NULL
    COMMENT '密钥失效时间，Asia/Shanghai';
ALTER TABLE `sc_project_app_credential_version`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_project_application`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_project_application`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_project_auth_nonce`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_project_auth_nonce`
    MODIFY COLUMN `expire_at` DATETIME(3) NOT NULL
    COMMENT '过期时间，Asia/Shanghai，取插入时刻加防重放窗口上限';
ALTER TABLE `sc_project_capability`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_project_capability`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_project_listing`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_project_listing`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_project_listing_version`
    MODIFY COLUMN `published_at` DATETIME(3) NOT NULL
    COMMENT '发布时间，Asia/Shanghai';
ALTER TABLE `sc_project_listing_version`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_raw_status_observation`
    MODIFY COLUMN `observed_at` DATETIME(3) NOT NULL
    COMMENT '供应商事实发生或报告时间，Asia/Shanghai';
ALTER TABLE `sc_raw_status_observation`
    MODIFY COLUMN `received_at` DATETIME(3) NOT NULL
    COMMENT '中台收到并落库时间，Asia/Shanghai';
ALTER TABLE `sc_reconciliation_batch`
    MODIFY COLUMN `period_start` DATETIME(3) NOT NULL
    COMMENT '对账账期开始时间，Asia/Shanghai';
ALTER TABLE `sc_reconciliation_batch`
    MODIFY COLUMN `period_end` DATETIME(3) NOT NULL
    COMMENT '对账账期结束时间，Asia/Shanghai';
ALTER TABLE `sc_reconciliation_batch`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '对账批次创建时间，Asia/Shanghai';
ALTER TABLE `sc_reconciliation_batch`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '对账批次最后更新时间，Asia/Shanghai';
ALTER TABLE `sc_reconciliation_batch`
    MODIFY COLUMN `completed_at` DATETIME(3) NULL
    COMMENT '对账批次完成时间，Asia/Shanghai';
ALTER TABLE `sc_reconciliation_difference`
    MODIFY COLUMN `observed_until` DATETIME(3) NULL
    COMMENT '单边缺失观察宽限截止时间，Asia/Shanghai';
ALTER TABLE `sc_reconciliation_difference`
    MODIFY COLUMN `resolved_at` DATETIME(3) NULL
    COMMENT '差异解决时间，Asia/Shanghai';
ALTER TABLE `sc_reconciliation_difference`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '差异创建时间，Asia/Shanghai';
ALTER TABLE `sc_reconciliation_difference`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '差异最后更新时间，Asia/Shanghai';
ALTER TABLE `sc_reconciliation_line`
    MODIFY COLUMN `occurred_at` DATETIME(3) NOT NULL
    COMMENT '账单事实发生时间，Asia/Shanghai';
ALTER TABLE `sc_reconciliation_line`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '标准账单行入库时间，Asia/Shanghai';
ALTER TABLE `sc_scenario_routing_candidate`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '候选配置创建时间，Asia/Shanghai';
ALTER TABLE `sc_scenario_routing_candidate`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '候选配置最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_scenario_routing_rule`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '规则创建时间，Asia/Shanghai';
ALTER TABLE `sc_scenario_routing_rule`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '规则最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_sensitive_access_audit`
    MODIFY COLUMN `accessed_at` DATETIME(3) NOT NULL
    COMMENT '访问发生时间，Asia/Shanghai';
ALTER TABLE `sc_settlement_adjustment_request`
    MODIFY COLUMN `approved_at` DATETIME(3) NULL
    COMMENT '审批完成时间，Asia/Shanghai';
ALTER TABLE `sc_settlement_adjustment_request`
    MODIFY COLUMN `executed_at` DATETIME(3) NULL
    COMMENT '调账命令执行时间，Asia/Shanghai';
ALTER TABLE `sc_settlement_adjustment_request`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '调账申请创建时间，Asia/Shanghai';
ALTER TABLE `sc_settlement_adjustment_request`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '调账申请最后更新时间，Asia/Shanghai';
ALTER TABLE `sc_standard_product_routing_candidate`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '候选配置创建时间，Asia/Shanghai';
ALTER TABLE `sc_standard_product_routing_candidate`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '候选配置最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_standard_product_routing_rule`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '规则创建时间，Asia/Shanghai';
ALTER TABLE `sc_standard_product_routing_rule`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '规则最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_status_mapping_definition`
    MODIFY COLUMN `published_at` DATETIME(3) NULL
    COMMENT '映射发布时间，Asia/Shanghai';
ALTER TABLE `sc_status_mapping_definition`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '映射创建时间，Asia/Shanghai';
ALTER TABLE `sc_supplier`
    MODIFY COLUMN `onboarded_at` DATETIME(3) NULL
    COMMENT '接入时间，Asia/Shanghai';
ALTER TABLE `sc_supplier`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_supplier`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_account_balance_snapshot`
    MODIFY COLUMN `observed_at` DATETIME(3) NOT NULL
    COMMENT '供应商账号余额观察时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_account_balance_snapshot`
    MODIFY COLUMN `valid_until` DATETIME(3) NOT NULL
    COMMENT '余额观察有效截止时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_account_balance_snapshot`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '余额快照入库时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_channel_binding`
    MODIFY COLUMN `valid_from` DATETIME(3) NOT NULL
    COMMENT '映射生效时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_channel_binding`
    MODIFY COLUMN `valid_to` DATETIME(3) NULL
    COMMENT '映射失效时间，Asia/Shanghai，空表示未到期';
ALTER TABLE `sc_supplier_channel_binding`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_channel_binding`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_config_publish_record`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '审计记录创建时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_connector`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_connector`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_connector_capability`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '能力记录创建时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_connector_version`
    MODIFY COLUMN `validated_at` DATETIME(3) NULL
    COMMENT '最近验证通过时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_connector_version`
    MODIFY COLUMN `validation_expires_at` DATETIME(3) NULL
    COMMENT '验证结论失效时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_connector_version`
    MODIFY COLUMN `published_at` DATETIME(3) NULL
    COMMENT '版本发布时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_connector_version`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_connector_version`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_contact`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_contact`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_delivery_region`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_error_code_map`
    MODIFY COLUMN `published_at` DATETIME(3) NULL
    COMMENT '映射发布时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_error_code_map`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '映射创建时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_external_sku_binding`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '投影最后更新时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_file_ref`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_integration_mode`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_offer`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_offer`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_offer_version`
    MODIFY COLUMN `valid_from` DATETIME(3) NOT NULL
    COMMENT '报价版本生效时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_offer_version`
    MODIFY COLUMN `valid_to` DATETIME(3) NULL
    COMMENT '报价版本失效时间，Asia/Shanghai，空表示长期有效';
ALTER TABLE `sc_supplier_offer_version`
    MODIFY COLUMN `published_at` DATETIME(3) NOT NULL
    COMMENT '报价版本发布时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_offer_version`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '版本记录创建时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_routing_policy`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '策略创建时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_routing_policy`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '策略最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_routing_policy_version`
    MODIFY COLUMN `valid_from` DATETIME(3) NOT NULL
    COMMENT '策略版本业务生效时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_routing_policy_version`
    MODIFY COLUMN `valid_to` DATETIME(3) NULL
    COMMENT '策略版本业务失效时间，Asia/Shanghai，空表示长期有效';
ALTER TABLE `sc_supplier_routing_policy_version`
    MODIFY COLUMN `published_at` DATETIME(3) NULL
    COMMENT '版本发布时间，Asia/Shanghai，草稿为空';
ALTER TABLE `sc_supplier_routing_policy_version`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '版本创建时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_routing_policy_version`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '版本最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_routing_record`
    MODIFY COLUMN `fact_captured_at` DATETIME(3) NULL
    COMMENT '本候选动态事实最近采集时间，Asia/Shanghai；未执行动态检查时为空';
ALTER TABLE `sc_supplier_routing_record`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '候选记录创建时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_routing_round`
    MODIFY COLUMN `computed_at` DATETIME(3) NOT NULL
    COMMENT '纯路由计算完成时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_routing_round`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '路由轮次入库时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_settlement_account`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_settlement_account`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_settlement_ledger`
    MODIFY COLUMN `occurred_at` DATETIME(3) NOT NULL
    COMMENT '资金事实发生时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_settlement_ledger`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '流水入库时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_supply_scope`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_supply_scope`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '最后修改时间，Asia/Shanghai';
ALTER TABLE `sc_supplier_supply_scope_mode`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE `sc_supply_chain_order`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '订单受理时间，Asia/Shanghai';
ALTER TABLE `sc_supply_chain_order`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '订单最后更新时间，Asia/Shanghai';
ALTER TABLE `sc_supply_chain_order`
    MODIFY COLUMN `completed_at` DATETIME(3) NULL
    COMMENT '订单进入业务终态时间，Asia/Shanghai';
ALTER TABLE `sc_timer_execution`
    MODIFY COLUMN `started_at` DATETIME(3) NOT NULL
    COMMENT '任务执行开始时间，Asia/Shanghai';
ALTER TABLE `sc_timer_execution`
    MODIFY COLUMN `finished_at` DATETIME(3) NULL
    COMMENT '任务执行结束时间，Asia/Shanghai';
ALTER TABLE `sc_timer_execution`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '执行审计入库时间，Asia/Shanghai';
ALTER TABLE `sc_timer_task`
    MODIFY COLUMN `next_fire_at` DATETIME(3) NOT NULL
    COMMENT '下一次计划触发时间，Asia/Shanghai';
ALTER TABLE `sc_timer_task`
    MODIFY COLUMN `lease_until` DATETIME(3) NULL
    COMMENT '当前任务租约到期时间，Asia/Shanghai';
ALTER TABLE `sc_timer_task`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL
    COMMENT '任务创建时间，Asia/Shanghai';
ALTER TABLE `sc_timer_task`
    MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL
    COMMENT '任务最后更新时间，Asia/Shanghai';


-- 2. 最后在同一事务中原地转换既有测试数据，不删除库、表或 Liquibase 历史记录。
UPDATE `sc_account_ledger`
SET `occurred_at` = DATE_ADD(`occurred_at`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR);

UPDATE `sc_b_order`
SET `started_at` = DATE_ADD(`started_at`, INTERVAL 8 HOUR),
    `completed_at` = DATE_ADD(`completed_at`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_brand`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_brand_alias`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_buyer`
SET `cooperation_started_at` = DATE_ADD(`cooperation_started_at`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_buyer_access_client`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_buyer_account`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_buyer_catalog_change`
SET `occurred_at` = DATE_ADD(`occurred_at`, INTERVAL 8 HOUR);

UPDATE `sc_buyer_category_scope`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR);

UPDATE `sc_buyer_contact`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_buyer_cooperation_mode`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR);

UPDATE `sc_buyer_credential_set`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_buyer_credential_version`
SET `valid_from` = DATE_ADD(`valid_from`, INTERVAL 8 HOUR),
    `valid_to` = DATE_ADD(`valid_to`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `activated_at` = DATE_ADD(`activated_at`, INTERVAL 8 HOUR),
    `retired_at` = DATE_ADD(`retired_at`, INTERVAL 8 HOUR);

UPDATE `sc_buyer_file_ref`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR);

UPDATE `sc_buyer_integration_call_log`
SET `started_at` = DATE_ADD(`started_at`, INTERVAL 8 HOUR),
    `completed_at` = DATE_ADD(`completed_at`, INTERVAL 8 HOUR);

UPDATE `sc_buyer_integration_capability`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR);

UPDATE `sc_buyer_integration_mode`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR);

UPDATE `sc_buyer_integration_profile`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_buyer_integration_profile_version`
SET `effective_at` = DATE_ADD(`effective_at`, INTERVAL 8 HOUR),
    `validated_at` = DATE_ADD(`validated_at`, INTERVAL 8 HOUR),
    `published_at` = DATE_ADD(`published_at`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_buyer_listing`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_buyer_listing_availability`
SET `evaluated_at` = DATE_ADD(`evaluated_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_buyer_listing_version`
SET `valid_from` = DATE_ADD(`valid_from`, INTERVAL 8 HOUR),
    `valid_to` = DATE_ADD(`valid_to`, INTERVAL 8 HOUR),
    `published_at` = DATE_ADD(`published_at`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR);

UPDATE `sc_buyer_product`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_buyer_product_l2_override`
SET `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_buyer_product_l2_override_version`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR);

UPDATE `sc_buyer_product_settlement_price`
SET `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_buyer_product_settlement_price_version`
SET `published_at` = DATE_ADD(`published_at`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR);

UPDATE `sc_buyer_project`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_buyer_tenant_binding`
SET `valid_from` = DATE_ADD(`valid_from`, INTERVAL 8 HOUR),
    `valid_to` = DATE_ADD(`valid_to`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_credential_slot`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_fulfillment_acceptance_receipt`
SET `accepted_at` = DATE_ADD(`accepted_at`, INTERVAL 8 HOUR),
    `rejected_at` = DATE_ADD(`rejected_at`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_fulfillment_asset`
SET `valid_from` = DATE_ADD(`valid_from`, INTERVAL 8 HOUR),
    `valid_until` = DATE_ADD(`valid_until`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_fulfillment_call_log`
SET `started_at` = DATE_ADD(`started_at`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR);

UPDATE `sc_fulfillment_exception_case`
SET `resolved_at` = DATE_ADD(`resolved_at`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_fulfillment_operation`
SET `next_recover_at` = DATE_ADD(`next_recover_at`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `completed_at` = DATE_ADD(`completed_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_fulfillment_request`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_fund_reservation`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_inbox_event`
SET `received_at` = DATE_ADD(`received_at`, INTERVAL 8 HOUR),
    `processed_at` = DATE_ADD(`processed_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_instore_channel_order`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR),
    `completed_at` = DATE_ADD(`completed_at`, INTERVAL 8 HOUR);

UPDATE `sc_instore_channel_user_binding`
SET `token_expires_at` = DATE_ADD(`token_expires_at`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_instore_entry_credential`
SET `expires_at` = DATE_ADD(`expires_at`, INTERVAL 8 HOUR),
    `processing_started_at` = DATE_ADD(`processing_started_at`, INTERVAL 8 HOUR),
    `used_at` = DATE_ADD(`used_at`, INTERVAL 8 HOUR),
    `revoked_at` = DATE_ADD(`revoked_at`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_instore_operation`
SET `next_recover_at` = DATE_ADD(`next_recover_at`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR),
    `completed_at` = DATE_ADD(`completed_at`, INTERVAL 8 HOUR);

UPDATE `sc_instore_payment_flow`
SET `pay_time` = DATE_ADD(`pay_time`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR),
    `completed_at` = DATE_ADD(`completed_at`, INTERVAL 8 HOUR);

UPDATE `sc_instore_refund_case`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR),
    `completed_at` = DATE_ADD(`completed_at`, INTERVAL 8 HOUR);

UPDATE `sc_instore_supplier_order`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR),
    `completed_at` = DATE_ADD(`completed_at`, INTERVAL 8 HOUR);

UPDATE `sc_inventory_snapshot`
SET `observed_at` = DATE_ADD(`observed_at`, INTERVAL 8 HOUR),
    `valid_until` = DATE_ADD(`valid_until`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR);

UPDATE `sc_inventory_source`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_o2o_channel_detail`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_o2o_channel_user_binding`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_o2o_operation`
SET `next_recover_at` = DATE_ADD(`next_recover_at`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR),
    `completed_at` = DATE_ADD(`completed_at`, INTERVAL 8 HOUR);

UPDATE `sc_o2o_refund_case`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR),
    `completed_at` = DATE_ADD(`completed_at`, INTERVAL 8 HOUR);

UPDATE `sc_o2o_supplier_order`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR),
    `completed_at` = DATE_ADD(`completed_at`, INTERVAL 8 HOUR);

UPDATE `sc_order_status_log`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR);

UPDATE `sc_outbox_event`
SET `available_at` = DATE_ADD(`available_at`, INTERVAL 8 HOUR),
    `lease_until` = DATE_ADD(`lease_until`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR),
    `published_at` = DATE_ADD(`published_at`, INTERVAL 8 HOUR);

UPDATE `sc_platform_settlement_price`
SET `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_platform_settlement_price_version`
SET `published_at` = DATE_ADD(`published_at`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR);

UPDATE `sc_platform_sku`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_platform_spu`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_product_category`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_project_app_credential_version`
SET `valid_from` = DATE_ADD(`valid_from`, INTERVAL 8 HOUR),
    `valid_to` = DATE_ADD(`valid_to`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR);

UPDATE `sc_project_application`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_project_auth_nonce`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `expire_at` = DATE_ADD(`expire_at`, INTERVAL 8 HOUR);

UPDATE `sc_project_capability`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_project_listing`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_project_listing_version`
SET `published_at` = DATE_ADD(`published_at`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR);

UPDATE `sc_raw_status_observation`
SET `observed_at` = DATE_ADD(`observed_at`, INTERVAL 8 HOUR),
    `received_at` = DATE_ADD(`received_at`, INTERVAL 8 HOUR);

UPDATE `sc_reconciliation_batch`
SET `period_start` = DATE_ADD(`period_start`, INTERVAL 8 HOUR),
    `period_end` = DATE_ADD(`period_end`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR),
    `completed_at` = DATE_ADD(`completed_at`, INTERVAL 8 HOUR);

UPDATE `sc_reconciliation_difference`
SET `observed_until` = DATE_ADD(`observed_until`, INTERVAL 8 HOUR),
    `resolved_at` = DATE_ADD(`resolved_at`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_reconciliation_line`
SET `occurred_at` = DATE_ADD(`occurred_at`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR);

UPDATE `sc_scenario_routing_candidate`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_scenario_routing_rule`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_sensitive_access_audit`
SET `accessed_at` = DATE_ADD(`accessed_at`, INTERVAL 8 HOUR);

UPDATE `sc_settlement_adjustment_request`
SET `approved_at` = DATE_ADD(`approved_at`, INTERVAL 8 HOUR),
    `executed_at` = DATE_ADD(`executed_at`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_standard_product_routing_candidate`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_standard_product_routing_rule`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_status_mapping_definition`
SET `published_at` = DATE_ADD(`published_at`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR);

UPDATE `sc_supplier`
SET `onboarded_at` = DATE_ADD(`onboarded_at`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_supplier_account_balance_snapshot`
SET `observed_at` = DATE_ADD(`observed_at`, INTERVAL 8 HOUR),
    `valid_until` = DATE_ADD(`valid_until`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR);

UPDATE `sc_supplier_channel_binding`
SET `valid_from` = DATE_ADD(`valid_from`, INTERVAL 8 HOUR),
    `valid_to` = DATE_ADD(`valid_to`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_supplier_config_publish_record`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR);

UPDATE `sc_supplier_connector`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_supplier_connector_capability`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR);

UPDATE `sc_supplier_connector_version`
SET `validated_at` = DATE_ADD(`validated_at`, INTERVAL 8 HOUR),
    `validation_expires_at` = DATE_ADD(`validation_expires_at`, INTERVAL 8 HOUR),
    `published_at` = DATE_ADD(`published_at`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_supplier_contact`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_supplier_delivery_region`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR);

UPDATE `sc_supplier_error_code_map`
SET `published_at` = DATE_ADD(`published_at`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR);

UPDATE `sc_supplier_external_sku_binding`
SET `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_supplier_file_ref`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR);

UPDATE `sc_supplier_integration_mode`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR);

UPDATE `sc_supplier_offer`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_supplier_offer_version`
SET `valid_from` = DATE_ADD(`valid_from`, INTERVAL 8 HOUR),
    `valid_to` = DATE_ADD(`valid_to`, INTERVAL 8 HOUR),
    `published_at` = DATE_ADD(`published_at`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR);

UPDATE `sc_supplier_routing_policy`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_supplier_routing_policy_version`
SET `valid_from` = DATE_ADD(`valid_from`, INTERVAL 8 HOUR),
    `valid_to` = DATE_ADD(`valid_to`, INTERVAL 8 HOUR),
    `published_at` = DATE_ADD(`published_at`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_supplier_routing_record`
SET `fact_captured_at` = DATE_ADD(`fact_captured_at`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR);

UPDATE `sc_supplier_routing_round`
SET `computed_at` = DATE_ADD(`computed_at`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR);

UPDATE `sc_supplier_settlement_account`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_supplier_settlement_ledger`
SET `occurred_at` = DATE_ADD(`occurred_at`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR);

UPDATE `sc_supplier_supply_scope`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);

UPDATE `sc_supplier_supply_scope_mode`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR);

UPDATE `sc_supply_chain_order`
SET `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR),
    `completed_at` = DATE_ADD(`completed_at`, INTERVAL 8 HOUR);

UPDATE `sc_timer_execution`
SET `started_at` = DATE_ADD(`started_at`, INTERVAL 8 HOUR),
    `finished_at` = DATE_ADD(`finished_at`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR);

UPDATE `sc_timer_task`
SET `next_fire_at` = DATE_ADD(`next_fire_at`, INTERVAL 8 HOUR),
    `lease_until` = DATE_ADD(`lease_until`, INTERVAL 8 HOUR),
    `created_at` = DATE_ADD(`created_at`, INTERVAL 8 HOUR),
    `updated_at` = DATE_ADD(`updated_at`, INTERVAL 8 HOUR);
