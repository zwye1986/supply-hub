# 商品批量上下架前端收尾实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: 使用 subagent-driven-development，一名实施者，先规格审查后质量审查。用户要求在当前分支先收尾批量前端；不建立新工作树。

**Goal:** 批量状态操作只发送一次批量写请求，准确展示完整回执，异常和旧契约不触发自动逐项重发。

**Architecture:** 保留后端34cff15逐项独立事务契约。前端在响应使用前验证完整覆盖，列表管理请求状态与只读结果明细；写结果和后续刷新失败分别反馈，不扩展公共Axios错误语义。

**Tech Stack:** Vue3、TypeScript、现有node:test与真实SFC内存renderer、ESLint、build:local。

**设计依据：** `docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#4110-批量上下架接口put-supply-chainproductsbatch-status`，2026-10-04收尾修订。前端当前基准b2b6ae3；原功能已由用户提交，不回退或删除其改动。后端部署脚本及.workbuddy未提交文件不属于本任务。

## Task 1：真实组件RED与最小修复

**Files:** 前端 `/Users/mac/Documents/codeSpace/supplychainhubweb/src/views/supplier/product/list/index.vue`、`src/api/product/{index,types}.ts`；按职责可新增同目录响应校验工具和结果明细组件；测试新增 `tests/product-batch-status.test.mjs` 及 `tests/support/product-list-sfc-harness.mjs`。后端代码不改，根任务负责全部docs。

- [x] 先用真实SFC setup与模板编译写RED，只替换HTTP边界/UI壳；不复制handleBatchStatus到测试中，不以正则文本存在或模拟实现代替行为测试。可复用现有supplier-config-sfc-harness的编译/renderer模式。
- [x] RED至少证明：Boolean响应受控处理而非undefined.length；DO_NOT_RETRY业务拒绝、鉴权、网络与系统错误无逐项写重试；请求在途与确认在途重复点击只发一次；超过200项不开写请求；失败21项全部可见、没有“其余详见日志”的不可见明细。
- [x] 运行 `node --test tests/product-batch-status.test.mjs`，保存 `/tmp/product-batch-frontend-red.log`；真实断言失败才计RED，编译/装配错误先修正。
- [x] 冻结本次选中SPU并使用命名200项上限，等待确认/写请求/读刷新期间busy保护，两按钮同时绑定disabled/loading；真实模板断言三个阶段并验证刷新在途重复点击不新增写。空选/越界明确提示。移除全量异常catch里的逐项写降级，任何失败不新增写请求。
- [x] 验证unknown响应为合法对象：succeeded/failed数组；成功编码字符串；失败SPU实体、编码、整数错误码、字符串原因；每个编码属于本次请求、无重复、成功失败合并完整覆盖本次请求。Boolean或非法回执受控报告契约错误，不伪造成功/失败清单。
- [x] 合法回执显示准确计数与全部失败明细，可滚动展示；原因作为文本，不用dangerouslyUseHTMLString。全成功/部分失败/全失败分别提示。回执未知提示需核对当前状态。
- [x] 操作发出后，无论合法响应或写结果未知，都尝试真实列表查询刷新；刷新失败独立反馈且保留原回执，不重新发送写请求；各异常路径解除busy，确认取消为预期UI控制流。
- [x] 补GREEN覆盖合法全成功、部分失败、全失败、取消确认、空选、200项边界、畸形/未知编码/重复/缺项回执、刷新失败不推翻写结果。运行所有前端测试、改动文件ESLint与 `pnpm build:local`，日志分别 `/tmp/product-batch-frontend-{green,eslint,build}.log`。
- [x] 自审后仅提交前端任务文件，中文提交信息，报告DONE和准确测试/检查范围。不要编辑后端docs或其它用户文件。

## Task 2：复审与验收记录

- [x] 根任务安排最终代码独立规格审查，问题由原实施者修复后复审；通过后独立质量审查。
- [x] 根任务更新 `docs/集成测试.md` 中§7.35及本任务执行记录，区分真实SFC自动化、构建与尚未做的浏览器/发布联调。
- [x] 前后端保持同批发布要求；本轮不执行部署或真实供应商调用。下一任务目标为用户指定HUA_SHENG固定价卡密全配置闭环，不替换成QNM验收。

实施记录：前端790f02a与bc47921完成，自审、独立规格复审及随后独立质量审查均通过。最终72项测试（43批量+原29）、改动ESLint与build:local通过，真实RED和准确日志见docs/集成测试.md§7.83。HTTP边界替换公共Axios，未证明其既有401刷新回放次数；本轮未进行浏览器联调、发布或供应商调用。
