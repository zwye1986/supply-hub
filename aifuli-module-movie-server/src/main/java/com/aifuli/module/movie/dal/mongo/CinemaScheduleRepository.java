package com.aifuli.module.movie.dal.mongo;

import com.aifuli.module.movie.dal.dataobject.mongo.CinemaScheduleDocument;
import com.aifuli.module.movie.framework.supply.MovieSupplyProperties.Account;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 账号影院快照仓库，完整写入成功才撤销旧场次；TTL 清理延迟不影响停售过滤。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#4-排期文档与一致性
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
            fields.append("refreshToken", token).append("updateTime", now);
            writes.add(new UpdateOneModel<>(new Document("_id", id), new Document("$set", fields)
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
        return onSaleCollection()
                .find(onSale(account).append("cinemaCode", cinemaCode).append("showDate", showDate.toString()))
                .sort(new Document("showTime", 1).append("_id", 1)).into(new ArrayList<>());
    }

    /** 同场次查询仍要求账号隔离，不能直接混合不同账号的供货价格。 */
    public List<Document> findOnSaleGroup(Account account, String showGroupId) {
        return onSaleCollection()
                .find(onSale(account).append("showGroupId", showGroupId)).into(new ArrayList<>());
    }

    /** 在售城市影片从未停售排期派生，TTL 异步清理不会导致已停售影片被展示。 */
    public List<String> findOnSaleFilmCodes(Account account, String cityCode) {
        return onSaleCollection()
                .distinct("filmCode", onSale(account).append("cityCode", cityCode), String.class).into(new ArrayList<>());
    }

    /** 统一在售条件，禁用账号直接拒绝查询。 */
    private Document onSale(Account account) {
        if (!account.isEnabled()) {
            throw new IllegalArgumentException("电影账号已禁用");
        }
        return scope(account).append("configVersion", account.getConfigVersion())
                .append("schemaVersion", CinemaScheduleDocument.SCHEMA_VERSION)
                .append("stopSellTime", new Document("$gt", Date.from(movieSupplyClock.instant())));
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
