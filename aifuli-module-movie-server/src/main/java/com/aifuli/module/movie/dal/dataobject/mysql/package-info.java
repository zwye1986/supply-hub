/**
 * 电影平台主数据和映射对象。不继承商城租户对象，也不假定 DDL 中存在逻辑删除字段。
 *
 * <p>六张平台表对象与 SQL 字段、注释一致，数据库维护创建和更新时间。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#3-主数据映射。</p>
 */
package com.aifuli.module.movie.dal.dataobject.mysql;
