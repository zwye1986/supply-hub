package com.aifuli.module.supplychain.tradecore.catalog.dal.mysql;

import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 平台定价根锁后的SKU普通重读，清除锁等待之前的MyBatis会话一级缓存。
 * 调用方须先取得平台定价根锁并处于供应链RC事务；不反向取得SKU排他锁。
 * 显式使用供应链工厂，template复用当前事务会话，仅清本会话缓存，不提交或改变全局配置。
 * 设计见 docs/superpowers/specs/2026-10-11-supplier-offer-lifecycle-design.md#34-闭环四同步重算的锁顺序与并发事务规范。
 */
@Component
public class PlatformSkuPricingInputReader {
    private final PlatformSkuMapper skuMapper;
    private final SqlSessionTemplate sqlSessionTemplate;

    public PlatformSkuPricingInputReader(PlatformSkuMapper skuMapper,
                                        @Qualifier("supplyChainSqlSessionFactory") SqlSessionFactory factory) {
        this.skuMapper = skuMapper;
        this.sqlSessionTemplate = new SqlSessionTemplate(factory);
    }

    public PlatformSkuDO readAfterRootLock(Long skuId) {
        sqlSessionTemplate.clearCache();
        return skuMapper.selectById(skuId);
    }
}
