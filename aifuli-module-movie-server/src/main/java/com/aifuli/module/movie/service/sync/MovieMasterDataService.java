package com.aifuli.module.movie.service.sync;

import com.aifuli.module.movie.dal.dataobject.mysql.*;
import com.aifuli.module.movie.dal.mysql.*;
import com.aifuli.module.movie.enums.MovieChannel;
import com.aifuli.module.movie.integration.supplier.SupplierMovieData.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Objects;
import static com.aifuli.module.movie.enums.MovieDataStatus.ENABLED;
import static com.aifuli.module.movie.enums.MovieDataStatus.NOW_SHOWING;
import static com.aifuli.module.movie.enums.MovieDataStatus.PRESELL;

/**
 * 标准主数据及 Mapping 的事务边界；不跨库，不把供应商 ID 写入标准主键。
 * 已有映射禁止自动改绑，禁用状态不会被后台采集覆盖。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#3-主数据映射
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MovieMasterDataService {
    /** 标准城市及渠道映射。 */
    private final MovieCityMapper cityMapper;
    private final MovieCityMappingMapper cityMappingMapper;
    /** 标准影片及渠道映射。 */
    private final MovieFilmMapper filmMapper;
    private final MovieFilmMappingMapper filmMappingMapper;
    /** 标准影院及渠道映射。 */
    private final MovieCinemaMapper cinemaMapper;
    private final MovieCinemaMappingMapper cinemaMappingMapper;

    /** 单条城市和映射同事务落库；JY 按参考项目的精确同名匹配规则补码。 */
    @Transactional(rollbackFor = Exception.class)
    public void syncCity(MovieChannel channel, City source) {
        log.debug("同步电影城市 channel={}, supplierCityId={}, code={}", channel, source.supplierId(), source.code());
        MovieCityMappingDO mapping = cityMappingMapper.selectOne(Wrappers.<MovieCityMappingDO>lambdaQuery()
                .eq(MovieCityMappingDO::getChannel, channel.code()).eq(MovieCityMappingDO::getSupplierCityId, source.supplierId()));
        MovieCityDO standard;
        if (mapping != null) {
            standard = require(cityMapper.selectById(mapping.getStandardCityId()), "城市映射悬空", source.supplierId());
            sameCode(source.code(), standard.getCityCode(), "城市编码冲突", source.supplierId());
        } else if (source.code() != null) {
            standard = cityMapper.selectOne(Wrappers.<MovieCityDO>lambdaQuery().eq(MovieCityDO::getCityCode, source.code()));
            if (standard == null) {
                standard = new MovieCityDO();
                standard.setCityCode(source.code());
                standard.setCityName(source.name());
                standard.setFirstLetter(source.firstLetter());
                standard.setIsHot(source.hot());
                standard.setStatus(ENABLED);
                cityMapper.insert(standard);
            }
        } else {
            List<MovieCityDO> candidates = cityMapper.selectList(Wrappers.<MovieCityDO>lambdaQuery()
                    .eq(MovieCityDO::getCityName, source.name()));
            if (candidates.size() != 1) {
                throw new MissingMovieMappingException("城市无唯一同名标准记录：" + source.supplierId());
            }
            standard = candidates.getFirst();
        }
        // JC/LX 提供标准编码时可刷新名称；JY 只刷新自身映射，避免反向覆盖标准名称。
        if (source.code() != null) {
            MovieCityDO update = new MovieCityDO();
            update.setId(standard.getId());
            update.setCityName(source.name());
            update.setFirstLetter(source.firstLetter());
            update.setIsHot(source.hot());
            cityMapper.updateById(update);
        }
        if (mapping == null) {
            mapping = new MovieCityMappingDO();
            mapping.setChannel(channel.code());
            mapping.setSupplierCityId(source.supplierId());
            mapping.setSupplierCityName(source.name());
            mapping.setStandardCityId(standard.getId());
            cityMappingMapper.insert(mapping);
        } else {
            // 只更新采集名称，不能把查询时的旧绑定回写覆盖管理员的明确绑定。
            cityMappingMapper.update(null, Wrappers.<MovieCityMappingDO>lambdaUpdate()
                    .eq(MovieCityMappingDO::getId, mapping.getId())
                    .set(MovieCityMappingDO::getSupplierCityName, source.name()));
        }
    }

    /** 按专资编码合并影片，同时保留每个供应商 ID 的独立映射。 */
    @Transactional(rollbackFor = Exception.class)
    public void syncFilm(MovieChannel channel, Film source) {
        log.debug("同步电影影片 channel={}, supplierFilmId={}, code={}", channel, source.supplierId(), source.code());
        if (source.supplierId() == null || source.supplierId().isBlank()) {
            throw new MissingMovieMappingException("供应商影片尚无原始 ID，不能建立正式映射");
        }
        MovieFilmMappingDO mapping = filmMappingMapper.selectOne(Wrappers.<MovieFilmMappingDO>lambdaQuery()
                .eq(MovieFilmMappingDO::getChannel, channel.code()).eq(MovieFilmMappingDO::getSupplierFilmId, source.supplierId()));
        MovieFilmDO standard;
        if (mapping != null) {
            standard = require(filmMapper.selectById(mapping.getStandardFilmId()), "影片映射悬空", source.supplierId());
            sameCode(source.code(), standard.getFilmCode(), "影片编码冲突", source.supplierId());
        } else {
            requireCode(source.code(), "影片缺少专资编码", source.supplierId());
            standard = filmMapper.selectOne(Wrappers.<MovieFilmDO>lambdaQuery().eq(MovieFilmDO::getFilmCode, source.code()));
            if (standard == null) {
                standard = new MovieFilmDO();
                standard.setFilmCode(source.code());
                standard.setStatus(ENABLED);
            }
        }
        // 使用新的更新对象，不回写读出的业务编码、人工状态及其它受保护属性。
        Long standardId = standard.getId();
        String standardCode = standard.getFilmCode();
        Integer previousPresell = standard.getIsPresell();
        if (standardId != null) {
            standard = new MovieFilmDO();
            standard.setId(standardId);
        }
        standard.setFilmName(source.name());
        standard.setPosterUrl(source.posterUrl());
        standard.setDuration(source.duration());
        standard.setPublishDate(source.publishDate());
        standard.setDirector(source.director());
        standard.setCastActors(source.castActors());
        standard.setIntro(source.intro());
        standard.setFilmType(source.filmType());
        standard.setScore(source.score());
        // 热映优先不依赖任务先后：待映响应不能把已经热映的标准影片回退为预售。
        // TODO：热映优先及回退规则待业务确认，见 docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#71-证据等级与待确认语义。
        if (!source.presell() || !Integer.valueOf(NOW_SHOWING).equals(previousPresell)) {
            standard.setIsPresell(source.presell() ? PRESELL : NOW_SHOWING);
        }
        if (standard.getId() == null) {
            filmMapper.insert(standard);
        } else {
            // 仅刷新采集属性，不把查询时的旧状态回写覆盖管理员的并发禁用。
            standard.setStatus(null);
            filmMapper.updateById(standard);
        }
        if (mapping == null) {
            mapping = new MovieFilmMappingDO();
            mapping.setChannel(channel.code());
            mapping.setSupplierFilmId(source.supplierId());
            mapping.setStandardFilmId(standard.getId());
            mapping.setFilmCode(standardCode);
            filmMappingMapper.insert(mapping);
        }
    }

    /** 影院及映射同事务写入；城市变化必须与标准影院一致，禁止默默搬迁到其它城市。 */
    @Transactional(rollbackFor = Exception.class)
    public void syncCinema(MovieChannel channel, Cinema source) {
        log.debug("同步电影影院 channel={}, supplierCinemaId={}, code={}", channel, source.supplierId(), source.code());
        MovieCityDO city = resolveCity(channel, source.supplierCityId());
        MovieCinemaMappingDO mapping = cinemaMappingMapper.selectOne(Wrappers.<MovieCinemaMappingDO>lambdaQuery()
                .eq(MovieCinemaMappingDO::getChannel, channel.code()).eq(MovieCinemaMappingDO::getSupplierCinemaId, source.supplierId()));
        MovieCinemaDO standard;
        if (mapping != null) {
            standard = require(cinemaMapper.selectById(mapping.getStandardCinemaId()), "影院映射悬空", source.supplierId());
            sameCode(source.code(), standard.getCinemaCode(), "影院编码冲突", source.supplierId());
        } else {
            requireCode(source.code(), "影院缺少专资编码", source.supplierId());
            standard = cinemaMapper.selectOne(Wrappers.<MovieCinemaDO>lambdaQuery().eq(MovieCinemaDO::getCinemaCode, source.code()));
            if (standard == null) {
                standard = new MovieCinemaDO();
                standard.setCinemaCode(source.code());
                standard.setStandardCityId(city.getId());
                standard.setStatus(ENABLED);
            }
        }
        if (!Objects.equals(standard.getStandardCityId(), city.getId())) {
            throw new IllegalStateException("影院所属城市冲突：" + source.supplierId());
        }
        // 只写采集属性；城市归属、标准编码和人工启停只在新建时赋值。
        Long standardId = standard.getId();
        String standardCode = standard.getCinemaCode();
        if (standardId != null) {
            standard = new MovieCinemaDO();
            standard.setId(standardId);
        }
        standard.setCinemaName(source.name());
        standard.setCityName(city.getCityName());
        standard.setRegionName(source.regionName());
        standard.setAddress(source.address());
        standard.setLongitude(source.longitude());
        standard.setLatitude(source.latitude());
        standard.setPhone(source.phone());
        if (standard.getId() == null) {
            cinemaMapper.insert(standard);
        } else {
            standard.setStatus(null);
            cinemaMapper.updateById(standard);
        }
        if (mapping == null) {
            mapping = new MovieCinemaMappingDO();
            mapping.setChannel(channel.code());
            mapping.setSupplierCinemaId(source.supplierId());
            mapping.setSupplierCinemaName(source.name());
            mapping.setStandardCinemaId(standard.getId());
            mapping.setCinemaCode(standardCode);
            mapping.setStatus(ENABLED);
            cinemaMappingMapper.insert(mapping);
        } else {
            cinemaMappingMapper.update(null, Wrappers.<MovieCinemaMappingDO>lambdaUpdate()
                    .eq(MovieCinemaMappingDO::getId, mapping.getId())
                    .set(MovieCinemaMappingDO::getSupplierCinemaName, source.name()));
        }
    }

    /** 返回渠道已映射城市，供影院同步使用原始城市 ID。 */
    public List<MovieCityMappingDO> cities(MovieChannel channel) {
        return cityMappingMapper.selectList(Wrappers.<MovieCityMappingDO>lambdaQuery()
                .eq(MovieCityMappingDO::getChannel, channel.code()).orderByAsc(MovieCityMappingDO::getId));
    }

    /** 影院以主键游标分页，避免全国影院一次性载入及 OFFSET 越翻越慢。 */
    public List<MovieCinemaMappingDO> cinemas(MovieChannel channel, long afterId) {
        return cinemaMappingMapper.selectList(Wrappers.<MovieCinemaMappingDO>lambdaQuery()
                .eq(MovieCinemaMappingDO::getChannel, channel.code()).gt(MovieCinemaMappingDO::getId, afterId)
                .orderByAsc(MovieCinemaMappingDO::getId).last("LIMIT 200"));
    }

    /** 解析标准影院，禁用状态由编排记录为供给标记，查询隐藏但不阻止采集。 */
    public MovieCinemaDO cinema(MovieCinemaMappingDO mapping) {
        return require(cinemaMapper.selectById(mapping.getStandardCinemaId()), "影院映射悬空", mapping.getSupplierCinemaId());
    }

    /** 按主键解析影院所属标准城市。 */
    public MovieCityDO city(Long id) {
        return require(cityMapper.selectById(id), "标准城市不存在", String.valueOf(id));
    }

    /**
     * 只读解析排期影片：优先 Mapping，缺失时按已有专资编码解析并保留排期原始 ID。
     * Mapping 仅由影片任务维护，避免独立调度后的排期任务与影片任务竞争插入。
     * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#5-同步调度与恢复
     */
    @Transactional(readOnly = true)
    public MovieFilmDO resolveFilm(MovieChannel channel, String supplierFilmId, String filmCode) {
        log.debug("解析排期影片映射 channel={}, supplierFilmId={}, filmCode={}", channel, supplierFilmId, filmCode);
        MovieFilmMappingDO mapping = filmMappingMapper.selectOne(Wrappers.<MovieFilmMappingDO>lambdaQuery()
                .eq(MovieFilmMappingDO::getChannel, channel.code()).eq(MovieFilmMappingDO::getSupplierFilmId, supplierFilmId));
        if (mapping != null) {
            MovieFilmDO film = require(filmMapper.selectById(mapping.getStandardFilmId()), "影片映射悬空", supplierFilmId);
            sameCode(filmCode, film.getFilmCode(), "排期影片编码冲突", supplierFilmId);
            return film;
        }
        requireCode(filmCode, "排期影片缺少编码", supplierFilmId);
        return require(filmMapper.selectOne(Wrappers.<MovieFilmDO>lambdaQuery()
                .eq(MovieFilmDO::getFilmCode, filmCode)), "排期影片未同步", supplierFilmId);
    }

    /** 用渠道城市 ID 查标准城市，禁止把原始城市 ID 直接当平台主键。 */
    private MovieCityDO resolveCity(MovieChannel channel, String supplierCityId) {
        MovieCityMappingDO mapping = require(cityMappingMapper.selectOne(Wrappers.<MovieCityMappingDO>lambdaQuery()
                .eq(MovieCityMappingDO::getChannel, channel.code()).eq(MovieCityMappingDO::getSupplierCityId, supplierCityId)),
                "渠道城市未映射", supplierCityId);
        return city(mapping.getStandardCityId());
    }

    /** 有权威编码时必须与既有映射相同，缺码允许复用人工建立的映射。 */
    private static void sameCode(String supplied, String stored, String reason, String id) {
        if (supplied != null && !supplied.equals(stored)) {
            throw new IllegalStateException(reason + "：" + id);
        }
    }

    /** 新建标准记录前要求业务编码，不生成临时假编码。 */
    private static void requireCode(String code, String reason, String id) {
        if (code == null || code.isBlank()) {
            throw new MissingMovieMappingException(reason + "：" + id);
        }
    }

    /** 空映射转换成明确业务错误，交给任务汇总。 */
    private static <T> T require(T value, String reason, String id) {
        if (value == null) {
            throw new MissingMovieMappingException(reason + "：" + id);
        }
        return value;
    }
}
