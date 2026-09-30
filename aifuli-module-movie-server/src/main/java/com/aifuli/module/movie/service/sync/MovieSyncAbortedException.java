package com.aifuli.module.movie.service.sync;

/**
 * 失锁或线程中断必须停止整轮，不能作为单条供给失败继续执行。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#72-锁快照与并发边界
 */
public class MovieSyncAbortedException extends IllegalStateException {
    public MovieSyncAbortedException(String message) {
        super(message);
    }

    public MovieSyncAbortedException(String message, Throwable cause) {
        super(message, cause);
    }
}
