# cxb-novo-mall-dev Nacos 配置

## 导入信息

- 命名空间名称：cxb-novo-mall-dev
- 命名空间 ID：cxb-novo-mall-dev
- 配置分组：DEFAULT_GROUP
- 配置格式：YAML
- 目标 Nacos 版本：2.5.1

## Data ID

- gateway-server-dev.yaml
- system-server-dev.yaml
- infra-server-dev.yaml
- member-server-dev.yaml
- pay-server-dev.yaml
- statistics-server-dev.yaml
- product-server-dev.yaml
- promotion-server-dev.yaml
- trade-server-dev.yaml

## 导入方式

1. 登录 Nacos 控制台：http://47.117.130.100:8848/nacos/
2. 切换到命名空间 `cxb-novo-mall-dev`。
3. 进入「配置管理」->「配置列表」->「导入配置」。
4. 上传 `cxb-novo-mall-dev-nacos-config.zip`，冲突策略按需要选择「覆盖」。

ZIP 内部结构采用 Nacos 2.x 可识别的 `DEFAULT_GROUP/{dataId}` 目录结构；`import/DEFAULT_GROUP` 目录保留了 ZIP 内的原始 YAML 文件。