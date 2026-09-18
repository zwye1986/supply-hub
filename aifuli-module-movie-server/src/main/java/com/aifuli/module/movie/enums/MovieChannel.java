package com.aifuli.module.movie.enums;

/** 电影供应商编码；库内兼容小写 channel，业务供应商使用枚举名。 */
public enum MovieChannel {
    /** 今晨供应商。 */
    JC("jc"),
    /** 佳意供应商。 */
    JY("jy");

    /** 数据库中的渠道编码。 */
    private final String code;

    MovieChannel(String code) {
        this.code = code;
    }

    /** 返回平台 Mapping 与排期使用的渠道编码。 */
    public String code() {
        return code;
    }
}
