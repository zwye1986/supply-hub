/**
 * 动态排期文档。金额必须显式映射为 BSON Decimal128，TTL 不替代查询中的停售过滤。
 *
 * <p>CinemaScheduleDocument 只声明集合与键结构；Date/Decimal128 和协议字段转换由 MovieScheduleConverter 完成。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#4-排期文档与一致性。</p>
 */
package com.aifuli.module.movie.dal.dataobject.mongo;
