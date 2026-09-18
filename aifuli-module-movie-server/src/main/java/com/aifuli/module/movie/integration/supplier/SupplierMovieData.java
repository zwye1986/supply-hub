package com.aifuli.module.movie.integration.supplier;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 供应商归一后的采集模型，原始 ID 与标准编码分离，金额统一人民币元。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#3-主数据映射
 */
public final class SupplierMovieData {
    private SupplierMovieData() {
    }

    /**
     * 城市采集数据。
     * @param supplierId 供应商原始城市ID
     * @param code 行政区划统一代码 (如 110100)，供应商未提供时为空
     * @param name 供应商原始城市名
     * @param firstLetter 首字母索引 (B)
     * @param hot 是否热门城市 0-否 1-是
     */
    public record City(String supplierId, String code, String name, String firstLetter, Integer hot) {
    }

    /**
     * 影片采集数据，预售标记由接口类型确定。
     * @param supplierId 供应商原始影片ID
     * @param code 国家专资影片编码 (12位唯一业务键)
     * @param name 影片标准中文名
     * @param posterUrl 海报封面图URL
     * @param duration 影片时长 (分钟)
     * @param publishDate 公映日期
     * @param director 导演姓名
     * @param castActors 主要演职员表
     * @param intro 影片剧情简介
     * @param filmType 类型标签 (科幻/动作/剧情)
     * @param score 大众评分 (如 9.3)
     * @param presell 是否预售，false 对应 0-热映中，true 对应 1-预售待映
     */
    public record Film(String supplierId, String code, String name, String posterUrl, Integer duration,
                       LocalDate publishDate, String director, String castActors, String intro,
                       String filmType, BigDecimal score, boolean presell) {
    }

    /**
     * 影院采集数据，城市标准 ID 在持久化时解析。
     * @param supplierId 供应商原始影院ID
     * @param code 国家专资办影院编码 (8位唯一业务键)
     * @param supplierCityId 供应商原始城市ID
     * @param name 供应商原始影院名
     * @param regionName 行政区名称 (如 朝阳区)
     * @param address 影院详细物理地址
     * @param longitude 百度/高德经度
     * @param latitude 百度/高德纬度
     * @param phone 影院联系电话
     */
    public record Cinema(String supplierId, String code, String supplierCityId, String name, String regionName,
                         String address, BigDecimal longitude, BigDecimal latitude, String phone) {
    }

    /**
     * 排期：全部时间已转为 UTC Instant；JC disableFlag 只描述特惠购票，不代表快票失效。
     * areas/priceOption 保留供应商原始结构，领域转换层只转换协议明确的金额字段。
     * @param showId 供应商原始排期ID
     * @param supplierFilmId 供应商原始影片ID
     * @param filmCode 专资编码快照
     * @param hallId 供应商原始影厅ID
     * @param hallName 影厅名称
     * @param versionType 语言制式
     * @param showTime 开场时间
     * @param closeTime 散场时间
     * @param stopSellTime 停售时间，MongoDB TTL 自动回收字段
     * @param netPrice 电影标准人民币基准价 (元)
     * @param minSettlementPrice 本渠道该场次起步结算成本 (元)
     * @param disableFlag JC 特惠购票禁用标记，不代表快票失效
     * @param areas 供应商原生分区价格信息
     * @param priceOption JC 快票价格选项
     * @param quickPrices JC 顶层快票价格快照，不以 disableFlag 判定快票失效
     */
    public record Schedule(String showId, String supplierFilmId, String filmCode, String hallId,
                           String hallName, String versionType, Instant showTime, Instant closeTime,
                           Instant stopSellTime, BigDecimal netPrice, BigDecimal minSettlementPrice,
                           Integer disableFlag, JsonNode areas, JsonNode priceOption, JsonNode quickPrices) {
    }
}
