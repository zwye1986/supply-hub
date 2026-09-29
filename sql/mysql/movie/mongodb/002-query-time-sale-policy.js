// 停售策略迁移到查询阶段；在独立电影 MongoDB 显式执行，不由应用执行 DDL。
// 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#83-排期映射与统一动态停售规则
// 升级先停止旧电影实例；新建环境先执行 001，再执行本脚本。
const movieSaleTopology = db.adminCommand({ hello: 1 });
if (!movieSaleTopology.setName && movieSaleTopology.msg !== "isdbgrid") {
    throw new Error("电影排期需要 MongoDB 副本集或分片集群");
}
if (!db.getCollectionNames().includes("cinema_schedule")) {
    throw new Error("请先执行 001-cinema-schedule-indexes.js 初始化电影集合");
}

// 只移除已知的单字段停售 TTL，不删除排期、不变更其它用途的索引。
for (const movieSaleIndex of db.cinema_schedule.getIndexes()) {
    if (movieSaleIndex.expireAfterSeconds !== undefined && movieSaleIndex.key.stopSellTime !== undefined) {
        if (Object.keys(movieSaleIndex.key).length !== 1 || movieSaleIndex.key.stopSellTime !== 1) {
            throw new Error("发现非预期停售 TTL 索引，请人工核对后再迁移");
        }
        db.cinema_schedule.dropIndex(movieSaleIndex.name);
    }
}

// 用 showTime > now + 提前量查询，避免对每条记录计算表达式导致范围索引失效。
db.cinema_schedule.createIndex(
    { channel: 1, connectorAccountRef: 1, configVersion: 1, cinemaCode: 1, showDate: 1, showTime: 1 },
    { name: "idx_account_cinema_show_time" }
);
db.cinema_schedule.createIndex(
    { channel: 1, connectorAccountRef: 1, configVersion: 1, showGroupId: 1, showTime: 1 },
    { name: "idx_account_group_show_time" }
);
db.cinema_schedule.createIndex(
    { channel: 1, connectorAccountRef: 1, configVersion: 1, cityCode: 1, showTime: 1, filmCode: 1 },
    { name: "idx_account_city_film_show_time" }
);

// 可重复执行；验证没有遗留自动按停售时间删除记录的 TTL。
if (db.cinema_schedule.getIndexes().some(movieSaleIndex =>
    movieSaleIndex.expireAfterSeconds !== undefined && movieSaleIndex.key.stopSellTime !== undefined)) {
    throw new Error("电影排期停售 TTL 尚未移除");
}
