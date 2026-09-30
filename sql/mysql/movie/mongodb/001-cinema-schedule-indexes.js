// 电影排期的部署索引脚本，仅使用 mongosh 执行，不是 MySQL SQL。
// 设计：docs/电影子系统与中台履约详细设计.md 第 2.2 节、第 5.1 节。
// 使用传入 URI 所选择的独立电影数据库，禁止误连商城或中台数据库。

// 完整影院快照依赖 MongoDB 事务；部署阶段先检查拓扑，不静默退回部分写入。
const movieTopology = db.adminCommand({ hello: 1 });
if (!movieTopology.setName && movieTopology.msg !== "isdbgrid") {
    throw new Error("电影排期同步需要 MongoDB 副本集或分片集群");
}

// 事务开始前由部署脚本显式建集合，应用侧不执行 DDL。
for (const movieCollection of ["cinema_schedule", "cinema_schedule_refresh"]) {
    if (!db.getCollectionNames().includes(movieCollection)) {
        db.createCollection(movieCollection);
    }
}

// 按影院、放映日期查询未停售排期。
db.cinema_schedule.createIndex({ cinemaCode: 1, showDate: 1, stopSellTime: 1 });

// 按逻辑场次聚合同场次的多供应商供给。
db.cinema_schedule.createIndex({ showGroupId: 1, channel: 1 });

// TTL 仅回收容量，所有在售查询仍必须包含 stopSellTime > now。
db.cinema_schedule.createIndex({ stopSellTime: 1 }, { expireAfterSeconds: 0 });

// 按城市快速检索仍有有效排期的影片。
db.cinema_schedule.createIndex({ cityCode: 1, stopSellTime: 1, filmCode: 1 });

// 账号影院快照刷新及差集删除，不允许多账号互相覆盖。
// 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#4-排期文档与一致性
db.cinema_schedule.createIndex(
    { channel: 1, connectorAccountRef: 1, supplierCinemaId: 1, refreshToken: 1 },
    { name: "idx_account_cinema_refresh" }
);

// 账号级在售查询，同时匹配配置版本，避免旧账号配置的成本被重新使用。
db.cinema_schedule.createIndex(
    { channel: 1, connectorAccountRef: 1, configVersion: 1, cinemaCode: 1, showDate: 1, stopSellTime: 1 },
    { name: "idx_account_cinema_sale" }
);

// 两类在售查询都按账号/配置版本隔离，避免全国数据上先扫描其它账号的结果再过滤。
db.cinema_schedule.createIndex(
    { channel: 1, connectorAccountRef: 1, configVersion: 1, showGroupId: 1, stopSellTime: 1 },
    { name: "idx_account_group_sale" }
);
db.cinema_schedule.createIndex(
    { channel: 1, connectorAccountRef: 1, configVersion: 1, cityCode: 1, stopSellTime: 1, filmCode: 1 },
    { name: "idx_account_city_film_sale" }
);

// 显式确认 TTL 是单字段零秒索引，部署异常直接失败而非带错索引启动同步。
const movieTtlIndexes = db.cinema_schedule.getIndexes().filter(index =>
    Object.keys(index.key).length === 1 && index.key.stopSellTime === 1 && Number(index.expireAfterSeconds) === 0
);
if (movieTtlIndexes.length !== 1) {
    throw new Error("电影排期 stopSellTime TTL 索引校验失败");
}
