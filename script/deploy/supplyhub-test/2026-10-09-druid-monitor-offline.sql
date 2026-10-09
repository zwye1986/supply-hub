-- 2026-10-09 去 Druid 迁移：MySQL 监控菜单（Druid 控制台）整体下线。
-- 设计见 docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md#53-监控端点广告过滤器与前端页面退出
-- 目标库：system 数据源（supplyhub-test 栈的 system 服务），菜单 id=111（path=druid，component_name=InfraDruid）。
-- 仅停用菜单与配置项，不删除历史数据；前端配套改动在 supplychainhubweb 仓库执行。
-- 注意：system_menu.status 语义为 CommonStatusEnum（0=开启，1=关闭），下线须置 1；
-- 监控地址配置实际存储于 infra_config 表（config_key='url.druid'，见 sql/mysql/ruoyi-vue-pro.sql），非 system_dict_data。
UPDATE system_menu
SET status = 1
WHERE path = 'druid'
  AND component_name = 'InfraDruid'
  AND deleted = b'0';

-- 监控地址配置项下线（前端 druid 页面读取该 key）
DELETE FROM infra_config
WHERE config_key = 'url.druid';
