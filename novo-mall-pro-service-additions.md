# novo-mall-pro 新增服务对比说明

> 本文保留源项目差异分析的历史口径，不代表当前仓库仍包含下列全部模块。当前模块边界以
> `docs/superpowers/specs/2026-09-08-remove-unused-modules-design.md` 为准。

## 对比口径

本文只对比 `D:\Jierongjingfu-project\novo-mall-pro` 相比 `D:\Jierongjingfu-project\novo-mall` 新增了哪些标准服务。

`novo-mall` 中自行新增或定制的服务不纳入对比范围，例如电影、外部接口、订货、京东 VOP、淘宝、调度等相关模块。

## 结论概览

`novo-mall-pro` 相比排除定制服务后的 `novo-mall`，主要新增了 6 个顶层业务模块，合计新增 14 个 Maven 子模块，新增 7 个可启动服务。

| 类型 | 新增数量 |
| --- | ---: |
| 顶层业务模块 | 6 |
| Maven 子模块 | 14 |
| 可启动服务 | 7 |

## novo-mall-pro 新增服务清单

| 新增顶层模块 | 子模块结构 | 可启动服务 |
| --- | --- | --- |
| `aifuli-module-ai` | `aifuli-module-ai-api`、`aifuli-module-ai-server` | `AiServerApplication` |
| `aifuli-module-erp` | `aifuli-module-erp-api`、`aifuli-module-erp-server` | `ErpServerApplication` |
| `aifuli-module-im` | `aifuli-module-im-api`、`aifuli-module-im-server` | `ImServerApplication` |
| `aifuli-module-iot` | `aifuli-module-iot-api`、`aifuli-module-iot-core`、`aifuli-module-iot-server`、`aifuli-module-iot-gateway` | `IoTServerApplication`、`IotGatewayServerApplication` |
| `aifuli-module-mes` | `aifuli-module-mes-api`、`aifuli-module-mes-server` | `MesServerApplication` |
| `aifuli-module-wms` | `aifuli-module-wms-api`、`aifuli-module-wms-server` | `WmsServerApplication` |

## 主要结构变化

### 1. 新增 AI 模块

当前项目新增 `aifuli-module-ai`，采用标准 `api + server` 结构：

- `aifuli-module-ai-api`
- `aifuli-module-ai-server`

该模块在旧项目中不存在。

### 2. 新增 ERP 模块

当前项目新增 `aifuli-module-erp`，采用标准 `api + server` 结构：

- `aifuli-module-erp-api`
- `aifuli-module-erp-server`

该模块在旧项目中不存在。

### 3. 新增 IM 模块

源项目新增 `aifuli-module-im`，采用标准 `api + server` 结构：

- `aifuli-module-im-api`
- `aifuli-module-im-server`

该模块在旧项目中不存在，当前仓库已按后续裁剪设计移除。

### 4. 新增 IOT 模块

当前项目新增 `aifuli-module-iot`，这是新增模块里结构变化最大的一个，不只是普通的 `api + server`。

它包含：

- `aifuli-module-iot-api`
- `aifuli-module-iot-core`
- `aifuli-module-iot-server`
- `aifuli-module-iot-gateway`

其中有两个可启动入口：

- `IoTServerApplication`
- `IotGatewayServerApplication`

说明 IOT 模块额外拆出了网关服务，整体结构比普通业务模块更复杂。

### 5. 新增 MES 模块

当前项目新增 `aifuli-module-mes`，采用标准 `api + server` 结构：

- `aifuli-module-mes-api`
- `aifuli-module-mes-server`

该模块在旧项目中不存在。

### 6. 新增 WMS 模块

当前项目新增 `aifuli-module-wms`，采用标准 `api + server` 结构：

- `aifuli-module-wms-api`
- `aifuli-module-wms-server`

该模块在旧项目中不存在。

## 不纳入本次对比的 novo-mall 定制模块

以下模块属于 `novo-mall` 自己新增或定制的服务，不作为 `novo-mall-pro` 新增项对比：

- `aifuli-module-kit`
- `aifuli-module-scheduler`
- `aifuli-module-external-server`
- `aifuli-module-order-server`
- `aifuli-module-movie-background-server`
- `aifuli-module-movie-datasync-server`
- `aifuli-module-movie-external-server`
- `aifuli-module-movie-h5-server`
- `aifuli-module-of-app`
- `aifuli-module-dc-jdvop`
- `aifuli-module-taobao`

## 总结

排除 `novo-mall` 自己新增的定制服务后，`novo-mall-pro` 的主要新增内容就是新版 aifuli-cloud 标准模块扩展：

`AI`、`ERP`、`IM`、`IOT`、`MES`、`WMS`

其中最需要重点关注的是 `IOT`，因为它不是普通的单服务结构，而是额外拆出了 `iot-core` 和 `iot-gateway`，并且有两个启动入口。
