/**
 * 今晨电影私有协议适配。复用多账号配置模型，不能把供应商账号写死为单例。
 *
 * <p>已实现主数据及排期读取。TODO：锁座、出票、查单与退款协议。
 * 设计：docs/business/电影子系统与中台履约详细设计.md 第 2.3、4.4 节。</p>
 */
package com.aifuli.module.movie.integration.supplier.jc;
