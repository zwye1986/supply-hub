# 京东 VOP Starter

提供以下能力：

- 使用独立 Redis 连接只读获取旧京东项目维护的 accessToken；
- Token Redis 连接不会覆盖或复用当前应用的主 Redis；
- 不获取、不刷新、不写入、不删除京东 Token；
- 京东 Router 协议签名及通用 `JdVopClient`；
- 与旧 `ProductServiceImpl#checkProduct` 等价的 `JdVopProductClient#checkProduct`；
- 京东订单提交、按第三方单号反查、订单详情查询和取消的 `JdVopOrderClient`。

## 配置

京东 Token Redis 必须单独配置。生产环境建议通过 Nacos 和环境变量注入密码：

```yaml
jd:
  vop:
    open-api:
      enabled: true
      app-key: ${JD_VOP_APP_KEY}
      app-secret: ${JD_VOP_APP_SECRET}
      token-redis:
        host: ${JD_VOP_TOKEN_REDIS_HOST}
        port: ${JD_VOP_TOKEN_REDIS_PORT:6379}
        password: ${JD_VOP_TOKEN_REDIS_PASSWORD}
        database: ${JD_VOP_TOKEN_REDIS_DATABASE:1}
        key: ${JD_VOP_TOKEN_REDIS_KEY}
        command-timeout: 3s
```

`token-redis` 连接封装在 starter 内部，不会注册为通用的
`RedisConnectionFactory` 或 `StringRedisTemplate` Bean。Token 管理器只执行 `GET`；
Redis 中不存在该 Key 或读取失败时会直接抛出异常，不会向京东申请或刷新 Token。

## 商品可采购校验

```java
// 业务服务中直接构造注入 JdVopProductClient。
// 无四级乡镇地址时传 null，避免向京东发送无效的 0。
JdAreaInfo area = new JdAreaInfo(1L, 72L, 2799L, null);
JdProductCheckRequest request = new JdProductCheckRequest(
        List.of(new JdSkuQuantity(100012043978L, 1)), area);
List<JdSkuSaleState> result = jdVopProductClient.checkProduct(request);
```

## 订单提交

订单客户端固定使用预存款支付并直接进入京东生产流程。网络超时后必须先调用
`queryOrderByThirdOrderId` 反查，不能生成新的第三方订单号重下。

交易履约任务同时处理退款取消：京东履约组内全部商品、全部数量均审批至待退款后，
先按第三方订单号查询实际父子单并调用京东取消；只有履约账本确认取消成功，支付退款
才允许继续。单个京东履约组暂不支持部分商品或部分数量退款。

如京东合同要求提交企业发票，必须从环境配置企业、银行和京东四级地址信息，
禁止把这些信息硬编码到业务代码：

```yaml
jd:
  vop:
    open-api:
      order:
        invoice:
          enabled: true
          company-name: ${JD_INVOICE_COMPANY_NAME}
          name: ${JD_INVOICE_RECEIVER_NAME}
          phone: ${JD_INVOICE_PHONE}
          reg-phone: ${JD_INVOICE_REG_PHONE}
          reg-company-name: ${JD_INVOICE_REG_COMPANY_NAME}
          reg-code: ${JD_INVOICE_REG_CODE}
          address: ${JD_INVOICE_ADDRESS}
          reg-address: ${JD_INVOICE_REG_ADDRESS}
          reg-bank: ${JD_INVOICE_REG_BANK}
          reg-bank-account: ${JD_INVOICE_REG_BANK_ACCOUNT}
          province-id: ${JD_INVOICE_PROVINCE_ID}
          city-id: ${JD_INVOICE_CITY_ID}
          county-id: ${JD_INVOICE_COUNTY_ID}
          town-id: ${JD_INVOICE_TOWN_ID}
```
