package com.aifuli.module.movie.integration.supplier;

import com.aifuli.module.movie.enums.MovieChannel;
import com.aifuli.module.movie.framework.supply.MovieSupplyProperties.Account;
import com.aifuli.module.movie.integration.supplier.SupplierMovieData.*;
import java.util.List;

/** 只读供给端口；失败抛异常，成功空列表才表示没有供给。 */
public interface MovieSupplyClient {
    /** 本适配器负责的渠道。 */
    MovieChannel channel();
    /** 拉取账号城市。 */
    List<City> cities(Account account);
    /** 完整拉取热映或待映影片，内部处理分页。 */
    List<Film> films(Account account, boolean presell);
    /** 完整拉取渠道城市下影院。 */
    List<Cinema> cinemas(Account account, String supplierCityId);
    /** 是否提供全国影院接口；默认保持 JC/JY 按城市同步与故障隔离。 */
    default boolean supportsAllCinemas() {
        return false;
    }
    /** 全国影院只应每账号每轮拉取一次；未声明能力的渠道禁止误调用。 */
    default List<Cinema> allCinemas(Account account) {
        throw new UnsupportedOperationException("当前电影渠道不提供全国影院接口");
    }
    /** 完整拉取账号影院排期，标准编码与渠道 ID 均明确传递。 */
    List<Schedule> schedules(Account account, String supplierCinemaId, String cinemaCode);
}
