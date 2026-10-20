# 合作商品状态列空白修复实施计划

**目标：** 固定生命周期枚举直接显示中文与颜色，不再依赖缺失的系统字典；空值及未知值不留白。

**设计：** `docs/superpowers/specs/2026-10-11-supplier-offer-lifecycle-design.md` §2，用户已确认。仅改前端映射与模板，不动上下架/发布、后端或数据库。前端目录固定为 `/Users/mac/codespace/supplychainhubweb`；保留工作区其他修改，不自行提交、推送、部署。

## 1. 测试先行

- [x] 基线：`node --test tests/offer-fulfillment.test.mjs tests/offer-product-list.test.mjs`，10 项通过。
- [x] `tests/offer-fulfillment.test.mjs` 增加四种状态的映射/颜色与 undefined/null/空串/未知值用例；`tests/offer-product-list.test.mjs` 增加无 Offer 字典时真实状态列文案与未知/空状态无操作断言。
- [x] 同一命令运行新增测试，26项中16项因映射缺失与状态列不显示文案而失败，原10项通过。

## 2. 最小实现

- [x] `src/views/supplier/manage/detail/utils/fulfillment.ts` 补齐 DRAFT/RETIRED 枚举、集中映射及 `getOfferStatusLabel` / `getOfferStatusType`。标签依次为草稿/已上架/已下架/已移除，PUBLISHED 为 success，其余 info。缺失返回 `-`，未知返回原码。中文注释链接设计 §2。
- [x] `src/views/supplier/manage/detail/ProductList.vue` 状态列由 DictTag 改为 ElTag 并调用上述函数，其余行为保持原样。
- [x] 定向26项全部通过。

## 3. 回归与交付

- [x] 全量463项、变更源码ESLint、测试与映射Prettier、开发构建通过。类型检查56条诊断与仅恢复本次源码至HEAD的临时副本基线一致（归一联合类型打印顺序），未修改无关代码。
- [x] 审查限定文件 diff 与 `git diff --check`，确认无接口/后端/按钮行为变更。
- [x] 在 `docs/集成测试.md` 增补前置条件、人工验收步骤、实际自动化结果及未部署/未实机验证边界。
