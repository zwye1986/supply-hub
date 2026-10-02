# 网关标准订单透传探针报告

- 时间：2026-10-02 22:26:30
- 结果：**PASS**
- git HEAD：`0b614310b8142337fee2b67539659f830d28971b`
- 网关 jar sha256：`402a27d9cd237beb4c5585738f560d16f4f996a927160a2042b781a4388ae452`
- 路由来源：application.yaml 实际路由 supply-chain-standard-orders（URI 指向本地挡板 ${AIFULI_GATEWAY_SUPPLY_CHAIN_URI}）
- 上游 chunked 编码：True
- chunked POST 探针路径：`/open-api/supply-chain/project/v1/orders/query?leak=secret`（HTTP 200）
- 未注册路径 /orders/other：http=200 code=404
- 上游故障异常安全：http=200 code=500

## 访问日志（关键行）

```
2026-10-02 22:26:27.963 [reactor-http-nio-3] INFO  com.aifuli.gateway.filter.logging.AccessLogFilter:109 - [writeAccessLog][网关日志：{
  "userId" : null,
  "userType" : null,
  "routeId" : "supply-chain-standard-orders",
  "schema" : "http",
  "requestUrl" : "/open-api/supply-chain/project/v1/orders/query",
  "queryParams" : null,
  "requestBody" : "[omitted]",
  "requestHeaders" : "null",
  "userIp" : "127.0.0.1",
  "responseBody" : "[omitted]",
  "responseHeaders" : null,
  "httpStatus" : "OK",
  "startTime" : "2026-10-02 22:26:27.854",
  "endTime" : "2026-10-02 22:26:27.913",
  "duration" : "58 ms"
}]
```

## 校验项

1. chunked POST body 完整透传上游：PASS
2. 上游响应原样返回客户端：PASS
3. 日志 requestBody/responseBody=[omitted]、queryParams=null、不泄露正文与 query：PASS
4. 未注册兄弟路径不进入标准订单路由：PASS
5. 上游故障异常安全（脱敏 CommonResult、不泄漏正文）：PASS
