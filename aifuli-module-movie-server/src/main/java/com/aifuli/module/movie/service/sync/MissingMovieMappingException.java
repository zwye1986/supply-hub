package com.aifuli.module.movie.service.sync;

/** 主数据无法唯一映射，供同步任务分类统计；不能据此删除整批排期。 */
public class MissingMovieMappingException extends RuntimeException {
    /** 只携带数据类型及非敏感原始 ID。 */
    public MissingMovieMappingException(String message) {
        super(message);
    }
}
