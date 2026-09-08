package com.aifuli.module.supplychain.infrastructure.configuration;

import com.alibaba.druid.pool.DruidDataSource;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import liquibase.integration.spring.SpringLiquibase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;

/**
 * 供应链中台独立数据库基础设施。
 *
 * <p>所有供应链 Mapper 和写事务必须显式绑定本配置声明的 Bean，禁止依赖宿主应用默认数据源。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SupplyChainDataSourceProperties.class)
@EnableTransactionManagement
@ConditionalOnProperty(prefix = "aifuli.supply-chain", name = "enabled", havingValue = "true")
@MapperScan(basePackages = "com.aifuli.module.supplychain",
        annotationClass = Mapper.class,
        sqlSessionFactoryRef = "supplyChainSqlSessionFactory")
public class SupplyChainDataSourceConfiguration {

    @Bean("supplyChainDataSource")
    DataSource supplyChainDataSource(SupplyChainDataSourceProperties properties) {
        DruidDataSource dataSource = properties.initializeDataSourceBuilder()
                .type(DruidDataSource.class)
                .build();
        // 每个物理连接都固定 UTC+8，CURRENT_TIMESTAMP(3) 才能稳定表示上海本地时间。
        // 设计见 docs/superpowers/specs/2026-09-04-supply-chain-database-local-time-design.md#21-存储类型与时区。
        dataSource.setConnectionInitSqls(List.of("SET time_zone = '+08:00'"));
        return dataSource;
    }

    @Bean
    SupplyChainDatabaseClock supplyChainDatabaseClock(
            @Qualifier("supplyChainDataSource") DataSource dataSource) {
        return new SupplyChainDatabaseClock(dataSource);
    }

    @Bean("supplyChainTransactionManager")
    JdbcTransactionManager supplyChainTransactionManager(
            @Qualifier("supplyChainDataSource") DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }

    @Bean("supplyChainSqlSessionFactory")
    SqlSessionFactory supplyChainSqlSessionFactory(
            @Qualifier("supplyChainDataSource") DataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);
        factory.setDataSource(dataSource);
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        factory.setPlugins(interceptor);
        return Objects.requireNonNull(factory.getObject());
    }

    @Bean("supplyChainLiquibase")
    SpringLiquibase supplyChainLiquibase(
            @Qualifier("supplyChainDataSource") DataSource dataSource) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml");
        liquibase.setShouldRun(true);
        return liquibase;
    }

}
