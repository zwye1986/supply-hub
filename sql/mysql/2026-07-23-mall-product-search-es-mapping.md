# 商品搜索 ES 索引 Mapping

## 说明

索引名称：

```text
mall_tenant_product_v1
```

文档粒度：

```text
一个租户商品池 SPU = 一条 ES 文档
documentId = tenantId + "_" + poolId
```

ES 只用于 H5 商品列表、分类筛选、关键词搜索、热销筛选、排序和分页。商品详情、SKU、购物车、下单仍以 MySQL 为准。

## 本地或测试环境重建索引

注意：下面命令会删除旧索引，仅用于本地或测试环境。生产环境需要先确认停写或重建方案。

```bash
curl -X DELETE "http://ES服务器IP:9200/mall_tenant_product_v1"
```

```bash
curl -X PUT "http://ES服务器IP:9200/mall_tenant_product_v1" \
  -H "Content-Type: application/json" \
  -d '{
    "settings": {
      "number_of_shards": 1,
      "number_of_replicas": 0
    },
    "mappings": {
      "dynamic": false,
      "properties": {
        "id": {
          "type": "keyword"
        },
        "tenantId": {
          "type": "long"
        },
        "poolId": {
          "type": "long"
        },
        "spuId": {
          "type": "long"
        },
        "name": {
          "type": "text",
          "fields": {
            "keyword": {
              "type": "keyword",
              "ignore_above": 256
            }
          }
        },
        "keyword": {
          "type": "text",
          "fields": {
            "keyword": {
              "type": "keyword",
              "ignore_above": 256
            }
          }
        },
        "introduction": {
          "type": "text"
        },
        "picUrl": {
          "type": "keyword",
          "index": false
        },
        "categoryId": {
          "type": "long"
        },
        "categoryPathIds": {
          "type": "long"
        },
        "hotType": {
          "type": "keyword"
        },
        "salePrice": {
          "type": "integer"
        },
        "marketPrice": {
          "type": "integer"
        },
        "stock": {
          "type": "integer"
        },
        "salesCount": {
          "type": "integer"
        },
        "sort": {
          "type": "integer"
        },
        "poolStatus": {
          "type": "integer"
        },
        "productStatus": {
          "type": "integer"
        },
        "sourceStatus": {
          "type": "integer"
        },
        "productType": {
          "type": "keyword"
        },
        "sourceType": {
          "type": "keyword"
        },
        "sourcePlatform": {
          "type": "keyword"
        },
        "updateTime": {
          "type": "long"
        }
      }
    }
  }'
```

## 验证索引结构

```bash
curl "http://ES服务器IP:9200/mall_tenant_product_v1/_mapping?pretty"
```

## 查询数据

```bash
curl "http://ES服务器IP:9200/mall_tenant_product_v1/_search?pretty"
```

检查点：

- `_source` 中不应该再出现 `_class` 字段。
- `introduction` 应该是短文本，不应该出现大段 CSS。
- 当 `marketPrice <= salePrice` 时，`marketPrice` 应为空。

