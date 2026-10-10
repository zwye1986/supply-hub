/**
 * 电影供应商操作执行与恢复。一次只执行中台指定的供应商，同一幂等键恢复，不自行换路。
 *
 * <p>TODO：操作登记、锁座、出票、解锁与查单恢复。
 * 设计：docs/business/电影子系统与中台履约详细设计.md 第 3.4、4.4 节。</p>
 */
package com.aifuli.module.movie.service.fulfillment;
