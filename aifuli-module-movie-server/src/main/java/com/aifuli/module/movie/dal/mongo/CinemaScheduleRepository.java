package com.aifuli.module.movie.dal.mongo;

import com.aifuli.module.movie.dal.dataobject.mongo.CinemaScheduleDocument;
import com.aifuli.module.movie.framework.supply.MovieSupplyProperties.Account;
import com.aifuli.module.movie.framework.supply.MovieSalePolicy;
import com.mongodb.client.MongoCollection;
import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.WriteModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 账号影院快照仓库，完整写入成功才撤销旧场次；停售只在查询时动态过滤。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#83-排期映射与统一动态停售规则
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class CinemaScheduleRepository {
    /** 每批写入上限，控制单次 MongoDB 请求内存。 */
    private static final int WRITE_BATCH_SIZE = 500;
    /** 独立电影 MongoDB 客户端。 */
    private final MongoTemplate mongoTemplate;
    /** Spring 配置的同一个电影 Mongo 客户端，用于显式事务会话。 */
    private final MongoClient mongoClient;
    /** 统一时间来源，不依赖本地默认时区。 */
    private final Clock movieSupplyClock;
    /** 动态停售提前量，仅在读取供给时使用，不能影响采集入库。 */
    private final MovieSalePolicy salePolicy;

    /** 轮次前只读检查部署前提，防止逐院外呼后才发现事务不可用或隐式建集合。 */
    public void verifyStorage() {
        Document topology = mongoTemplate.executeCommand(new Document("hello", 1));
        if (topology.getString("setName") == null && !"isdbgrid".equals(topology.getString("msg"))) {
            throw new IllegalStateException("电影排期需要支持事务的 MongoDB 副本集或分片集群");
        }
        if (!mongoTemplate.collectionExists(CinemaScheduleDocument.COLLECTION)
                || !mongoTemplate.collectionExists(CinemaScheduleDocument.REFRESH_COLLECTION)) {
            throw new IllegalStateException("请先部署电影排期集合与刷新版本集合");
        }
        // 旧 TTL 会物理删除已停售但仍由上游返回的场次，必须先由部署迁移移除。
        for (Document index : mongoTemplate.getCollection(CinemaScheduleDocument.COLLECTION).listIndexes()) {
            Document keys = index.get("key", Document.class);
            if (keys != null && keys.containsKey("stopSellTime") && index.containsKey("expireAfterSeconds")) {
                throw new IllegalStateException("请先执行 002-query-time-sale-policy.js 移除排期停售 TTL");
            }
        }
    }

    /**
     * 外呼前申请刷新版本；旧响应不能在重试时重新申请版本，防止旧任务覆盖新快照。
     * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#72-锁快照与并发边界
     */
    public Refresh beginRefresh(Account account, String supplierCinemaId, Runnable guard) {
        guard.run();
        String id = CinemaScheduleDocument.key(account.getChannel().code(), account.getAccountRef(), supplierCinemaId);
        Document result = refreshes().findOneAndUpdate(new Document("_id", id),
                new Document("$inc", new Document("revision", 1L)),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        if (result == null) {
            throw new IllegalStateException("影院刷新版本申请失败");
        }
        guard.run();
        return new Refresh(id, result.getLong("revision"));
    }

    /** 不可变的单影院刷新凭据，跨账号/影院传递会被拒绝。 */
    public record Refresh(String scopeId, long revision) {
    }

    /** 整院事务：版本条件更新、各批 upsert 与旧排期删除同成同败，失败不得退化为非事务写入。 */
    public void replace(Account account, String supplierCinemaId, List<Document> documents, Refresh refresh, Runnable guard) {
        log.debug("写入电影排期 account={}, supplierCinemaId={}, count={}", account.getAccountRef(), supplierCinemaId, documents.size());
        if (!CinemaScheduleDocument.key(account.getChannel().code(), account.getAccountRef(), supplierCinemaId).equals(refresh.scopeId())) {
            throw new IllegalArgumentException("影院刷新凭据归属错误");
        }
        Set<String> ids = new HashSet<>();
        for (Document document : documents) {
            String showId = document.getString("supplierShowId");
            if (showId == null || !CinemaScheduleDocument.key(account.getChannel().code(), account.getAccountRef(),
                    supplierCinemaId, showId).equals(document.getString("_id")) || !ids.add(document.getString("_id"))
                    || !Integer.valueOf(CinemaScheduleDocument.SCHEMA_VERSION).equals(document.getInteger("schemaVersion"))
                    || !account.getConfigVersion().equals(document.getString("configVersion"))
                    || !account.getAccountRef().equals(document.getString("connectorAccountRef"))
                    || !supplierCinemaId.equals(document.getString("supplierCinemaId"))
                    || !account.getChannel().code().equals(document.getString("channel"))) {
                throw new IllegalArgumentException("排期快照存在重复 ID 或账号影院归属错误");
            }
        }
        MongoCollection<Document> collection = mongoTemplate.getCollection(CinemaScheduleDocument.COLLECTION);
        String token = UUID.randomUUID().toString();
        Date now = Date.from(movieSupplyClock.instant());
        try (ClientSession session = mongoClient.startSession()) {
            session.withTransaction(() -> {
                guard.run();
                // 先写共同的版本文档：新一轮申请版本与旧一轮提交在 Mongo 内形成写冲突。
                long matched = refreshes().updateOne(session,
                        new Document("_id", refresh.scopeId()).append("revision", refresh.revision()),
                        new Document("$set", new Document("committedToken", token))).getMatchedCount();
                if (matched != 1) {
                    throw new IllegalStateException("影院快照已被更新的刷新版本取代，请重新拉取后重跑");
                }
                writeSnapshot(session, collection, account, supplierCinemaId, documents, token, now, guard);
                guard.run();
                return null;
            }, TransactionOptions.builder().readConcern(ReadConcern.SNAPSHOT)
                    .writeConcern(WriteConcern.MAJORITY).readPreference(ReadPreference.primary())
                    .maxCommitTime(30L, TimeUnit.SECONDS).build());
        }
    }

    /** 事务内无供应商外呼，每批次和删除前重新检查中断及会话锁。 */
    private void writeSnapshot(ClientSession session, MongoCollection<Document> collection, Account account,
                               String supplierCinemaId, List<Document> documents, String token, Date now, Runnable guard) {
        List<WriteModel<Document>> writes = new ArrayList<>(WRITE_BATCH_SIZE);
        for (Document source : documents) {
            Document fields = new Document(source);
            Object id = fields.remove("_id");
            // 写入边界拒绝固化停售策略，兼容调用方传入带旧字段的文档。
            fields.remove("stopSellTime");
            fields.append("refreshToken", token).append("updateTime", now);
            writes.add(new UpdateOneModel<>(new Document("_id", id), new Document("$set", fields)
                    // 删除旧版本固化的停售时间，防止未来读者误用过期策略；不删除场次本身。
                    .append("$unset", new Document("stopSellTime", ""))
                    .append("$setOnInsert", new Document("createTime", now)), new UpdateOptions().upsert(true)));
            if (writes.size() >= WRITE_BATCH_SIZE) {
                guard.run();
                collection.bulkWrite(session, writes, new BulkWriteOptions().ordered(true));
                writes.clear();
            }
        }
        if (!writes.isEmpty()) {
            guard.run();
            collection.bulkWrite(session, writes, new BulkWriteOptions().ordered(true));
        }
        guard.run();
        collection.deleteMany(session, scope(account).append("supplierCinemaId", supplierCinemaId)
                .append("refreshToken", new Document("$ne", token)));
    }

    /** 按明确账号、影院和放映日期查询在售排期，旧配置版本不能参与新报价。 */
    public List<Document> findOnSale(Account account, String cinemaCode, LocalDate showDate) {
        log.debug("查询影院在售排期 account={}, cinemaCode={}, showDate={}", account.getAccountRef(), cinemaCode, showDate);
        SaleQuery query = onSale(account);
        return withStopSellTime(onSaleCollection()
                .find(query.filter().append("cinemaCode", cinemaCode).append("showDate", showDate.toString()))
                .sort(new Document("showTime", 1).append("_id", 1)).into(new ArrayList<>()), query.leadTime());
    }

    /** 同场次查询仍要求账号隔离，不能直接混合不同账号的供货价格。 */
    public List<Document> findOnSaleGroup(Account account, String showGroupId) {
        log.debug("查询同场次在售排期 account={}, showGroupId={}", account.getAccountRef(), showGroupId);
        SaleQuery query = onSale(account);
        return withStopSellTime(onSaleCollection()
                .find(query.filter().append("showGroupId", showGroupId)).into(new ArrayList<>()), query.leadTime());
    }

    /** 在售城市影片从动态过滤后的排期派生，所有场次停售后影片不再输出。 */
    public List<String> findOnSaleFilmCodes(Account account, String cityCode) {
        log.debug("查询城市在售影片 account={}, cityCode={}", account.getAccountRef(), cityCode);
        return onSaleCollection()
                .distinct("filmCode", onSale(account).filter().append("cityCode", cityCode), String.class).into(new ArrayList<>());
    }

    /** 统一在售条件，禁用账号直接拒绝查询。 */
    private SaleQuery onSale(Account account) {
        if (!account.isEnabled()) {
            throw new IllegalArgumentException("电影账号已禁用");
        }
        Duration leadTime = salePolicy.snapshot();
        log.debug("电影供给查询停售策略 account={}, stopSellBeforeStart={}", account.getAccountRef(), leadTime);
        Document filter = scope(account).append("configVersion", account.getConfigVersion())
                .append("schemaVersion", CinemaScheduleDocument.SCHEMA_VERSION)
                .append("supplyEnabled", new Document("$ne", false))
                .append("showTime", new Document("$gt", Date.from(movieSupplyClock.instant().plus(leadTime))));
        return new SaleQuery(filter, leadTime);
    }

    /** 单次查询的过滤条件与策略快照，避免 Nacos 刷新造成过滤和输出不一致。 */
    private record SaleQuery(Document filter, Duration leadTime) {
    }

    /** 返回当前策略计算的停售时间，仅修改查询结果对象，不回写 MongoDB。 */
    private List<Document> withStopSellTime(List<Document> documents, Duration leadTime) {
        for (Document document : documents) {
            document.put("stopSellTime", Date.from(document.getDate("showTime").toInstant().minus(leadTime)));
        }
        return documents;
    }

    /** 账号引用与渠道共同限定数据所有权。 */
    private Document scope(Account account) {
        return new Document("channel", account.getChannel().code()).append("connectorAccountRef", account.getAccountRef());
    }

    /** 在售读取只使用主节点已获多数派确认的结果，不受 URI 次节点偏好影响。 */
    private MongoCollection<Document> onSaleCollection() {
        return mongoTemplate.getCollection(CinemaScheduleDocument.COLLECTION)
                .withReadPreference(ReadPreference.primary()).withReadConcern(ReadConcern.MAJORITY);
    }

    /** 刷新版本使用多数派确认，避免主节点切换后回退到已被淘汰的版本。 */
    private MongoCollection<Document> refreshes() {
        return mongoTemplate.getCollection(CinemaScheduleDocument.REFRESH_COLLECTION)
                .withWriteConcern(WriteConcern.MAJORITY);
    }
}
