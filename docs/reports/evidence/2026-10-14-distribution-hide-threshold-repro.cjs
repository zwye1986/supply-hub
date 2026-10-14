/**
 * 当前前端源码的只读复现：直接抽取并执行实际函数与校验器，不重写业务逻辑。
 * 这是函数级证据，不等于浏览器、真实 HTTP 或 MySQL 联调。
 * 运行：node docs/reports/evidence/2026-10-14-distribution-hide-threshold-repro.cjs
 */
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { createRequire } = require('node:module');
const root = process.argv[2] || '/Users/mac/codespace/supplychainhubweb';
const localRequire = createRequire(path.join(root, 'package.json'));
const ts = localRequire('typescript');
const { parse } = localRequire('vue/compiler-sfc');
const base = path.join(root, 'src/views/supplier/distribution/distributor/detail');
const sources = ['ProductList.vue', 'DistributionPriceForm.vue'].map(name => ({
  name,
  source: fs.readFileSync(path.join(base, name), 'utf8')
}));

function extract(source, names) {
  const { descriptor } = parse(source);
  const code = descriptor.scriptSetup.content;
  const ast = ts.createSourceFile('actual.ts', code, ts.ScriptTarget.Latest, true);
  const statements = ast.statements.filter(statement =>
    ts.isVariableStatement(statement) && statement.declarationList.declarations.some(declaration =>
      ts.isIdentifier(declaration.name) && names.includes(declaration.name.text)));
  assert.equal(statements.length, names.length, '源码结构变化时停止，避免执行错误替代函数');
  return statements.map(statement => statement.getText(ast)).join('\n');
}
const names = ['createPriceConfig', 'inferPriceFromRow', 'toOptionalNumber', 'buildFormulaRule', 'buildPricingPayload'];
const code = extract(sources[0].source, names) + '\n' +
  extract(sources[1].source, ['validateCustomRule']) + '\n' +
  'globalThis.actual = { inferPriceFromRow, buildPricingPayload, validateCustomRule };';
const compiled = ts.transpileModule(code, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS }
}).outputText;
const sandbox = { props: { optional: false, priceLabel: '分销价' } };
vm.runInNewContext(compiled, sandbox);
const actual = sandbox.actual;
const results = [];
function check(name, run) {
  run();
  results.push({ name, observed: true });
}
function form(threshold, taxIncluded = true) {
  return {
    distributionRule: { mode: 'DIRECT_MARKET_PRICE' },
    distributionHideThresholdPercent: threshold,
    taxIncluded,
    taxRateCode: taxIncluded ? 'RATE_6' : ''
  };
}
const payload = value => actual.buildPricingPayload('BPD_REVIEW_FIXTURE', value);
const row = { pricingRule: '等于市场价', taxIncluded: true, taxRateCode: 'RATE_6' };

check('输入2按2%原值提交，明确覆盖', () => {
  const request = payload(form(2));
  assert.equal(request.hideInherited, false);
  assert.equal(request.distributionHideThresholdPercent, 2);
});
check('空输入明确切换为继承', () => {
  const request = payload(form(undefined));
  assert.equal(request.hideInherited, true);
  assert.equal('distributionHideThresholdPercent' in request, false);
});
check('0是明确覆盖，不能当作空值', () => {
  const request = payload(form(0));
  assert.equal(request.hideInherited, false);
  assert.equal(request.distributionHideThresholdPercent, 0);
});
check('2.25原值保留，无整数截断', () => {
  assert.equal(payload(form(2.25)).distributionHideThresholdPercent, 2.25);
});
check('列表旧阈值为null，回填空并在再次提交时切换继承', () => {
  const filled = actual.inferPriceFromRow({ ...row, displayThresholdPercent: null });
  assert.equal(filled.distributionHideThresholdPercent, undefined);
  assert.equal(payload(filled).hideInherited, true);
});
check('列表旧阈值为7，回填并再次提交7', () => {
  const filled = actual.inferPriceFromRow({ ...row, displayThresholdPercent: 7 });
  assert.equal(filled.distributionHideThresholdPercent, 7);
  assert.equal(payload(filled).distributionHideThresholdPercent, 7);
});
check('列表即使追加新阈值2，现有回填仍使用旧阈值7', () => {
  const filled = actual.inferPriceFromRow({ ...row, displayThresholdPercent: 7,
    distributionHideThresholdPercent: 2 });
  assert.equal(filled.distributionHideThresholdPercent, 7);
});
check('自定义公式文案无法恢复参数，实际校验器拒绝空百分比', () => {
  const filled = actual.inferPriceFromRow({ ...row, pricingRule: '自定义公式' });
  assert.equal(filled.distributionRule.percentage, undefined);
  let error;
  actual.validateCustomRule.validator({}, filled.distributionRule, value => { error = value; });
  assert.equal(error.message, '请填写大于 0 的分销价百分比');
});
check('每次保存固定将销售价与服务费改为继承', () => {
  const request = payload(form(2));
  assert.equal(request.salesInherited, true);
  assert.equal(request.serviceFeeInherited, true);
});
check('隐藏组覆盖没有携带原销售区阈值', () => {
  const request = payload({ ...form(2), salesHideThresholdPercent: 5 });
  assert.equal('salesHideThresholdPercent' in request, false);
});
check('不含税覆盖请求遗漏NON_TAXABLE编码', () => {
  const request = payload(form(2, false));
  assert.equal(request.taxInherited, false);
  assert.equal(request.taxIncluded, false);
  assert.equal('taxRateCode' in request, false);
});
check('税务回填取有效值，再次提交强制声明覆盖', () => {
  const filled = actual.inferPriceFromRow({ ...row, taxInherited: true });
  assert.equal(payload(filled).taxInherited, false);
  assert.equal(payload(filled).taxRateCode, 'RATE_6');
});
console.log(JSON.stringify({
  evidenceLevel: 'actual-source-function-execution',
  sourceRoot: root,
  sourceHashes: sources.map(item => ({ file: item.name,
    sha256: crypto.createHash('sha256').update(item.source).digest('hex') })),
  confirmedCases: results.length,
  results
}, null, 2));
