/**
 * 动态排期文档。金额必须显式映射为 BSON Decimal128，TTL 不替代查询中的停售过滤。
 *
 * <p>TODO：CinemaScheduleDO 与金额映射。
 * 设计：docs/电影子系统与中台履约详细设计.md 第 2.2 节。</p>
 */
package com.aifuli.module.movie.dal.dataobject.mongo;
