-- 2026-10-09 去 Druid 迁移：MySQL 监控菜单（Druid 控制台）整体下线。
-- 设计见 docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md#53-监控端点广告过滤器与前端页面退出
-- 目标库：system 数据源（supplyhub-test 栈的 system 服务），菜单 id=111（path=druid，component_name=InfraDruid）。
-- supplyhub-test 为三服务（system/infra/member）共用单库，infra_config 属 infra 模块表，在本栈单库执行。
-- 下线方式：菜单停用（status=1，本仓 CommonStatusEnum 语义 0=开启/1=关闭，MenuServiceImpl 过滤 isDisable 节点）；
-- url.druid 配置项以物理删除下线（infra_config 无 status 列，该行为配置项而非监控历史数据，本脚本不删除任何监控历史数据）。
-- 前端配套改动在 supplychainhubweb 仓库执行。
UPDATE system_menu
SET status = 1
WHERE path = 'druid'
  AND component_name = 'InfraDruid'
  AND deleted = b'0';

-- 监控地址配置项下线（前端 druid 页面读取该 key，实际存储于 infra_config.config_key，见 sql/mysql/ruoyi-vue-pro.sql）
DELETE FROM infra_config
WHERE config_key = 'url.druid';
