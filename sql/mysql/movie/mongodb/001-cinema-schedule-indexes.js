// 电影排期的部署索引脚本，仅使用 mongosh 执行，不是 MySQL SQL。
// 设计：docs/电影子系统与中台履约详细设计.md 第 2.2 节、第 5.1 节。
// 使用传入 URI 所选择的独立电影数据库，禁止误连商城或中台数据库。

// 按影院、放映日期查询未停售排期。
db.cinema_schedule.createIndex({ cinemaCode: 1, showDate: 1, stopSellTime: 1 });

// 按逻辑场次聚合同场次的多供应商供给。
db.cinema_schedule.createIndex({ showGroupId: 1, channel: 1 });

// TTL 仅回收容量，所有在售查询仍必须包含 stopSellTime > now。
db.cinema_schedule.createIndex({ stopSellTime: 1 }, { expireAfterSeconds: 0 });

// 按城市快速检索仍有有效排期的影片。
db.cinema_schedule.createIndex({ cityCode: 1, stopSellTime: 1, filmCode: 1 });
