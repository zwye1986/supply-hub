/**
 * 电影平台 MyBatis Plus Mapper。使用 Lambda 条件，不跨库访问商城或中台数据。
 *
 * <p>六张平台表以标准编码和渠道原始 ID 唯一键支持幂等同步。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#3-主数据映射。</p>
 */
package com.aifuli.module.movie.dal.mysql;
