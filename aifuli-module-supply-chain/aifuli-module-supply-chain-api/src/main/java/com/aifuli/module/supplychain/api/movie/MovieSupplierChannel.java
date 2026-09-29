package com.aifuli.module.supplychain.api.movie;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** 电影配置渠道与中台连接器类型的唯一映射，避免散落业务字符串。 */
public enum MovieSupplierChannel {
    JC("MOVIE_JC_V1"), JY("MOVIE_JY_V1"), LX("MOVIE_LX_V1");

    /** 中台登记的电影协议适配类型。 */
    private final String adapterType;

    MovieSupplierChannel(String adapterType) {
        this.adapterType = adapterType;
    }

    /** 返回渠道对应的受控适配类型。 */
    public String adapterType() {
        return adapterType;
    }

    /** 解析Nacos的逗号分隔渠道；空串关闭全部，未知值及重复值失败关闭。 */
    public static List<MovieSupplierChannel> parseSelection(String configured) {
        if (configured == null || configured.isBlank()) {
            return List.of();
        }
        List<MovieSupplierChannel> channels = new ArrayList<>();
        for (String value : configured.split(",", -1)) {
            try {
                channels.add(valueOf(value.trim()));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("movie.supply.enabled-channels仅支持JC,JY,LX，以逗号分隔", exception);
            }
        }
        if (new HashSet<>(channels).size() != channels.size()) {
            throw new IllegalArgumentException("movie.supply.enabled-channels不得重复填写渠道");
        }
        return List.copyOf(channels);
    }
}
