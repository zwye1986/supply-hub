package com.aifuli.module.supplychain.infrastructure.outbox;

import com.aifuli.module.supplychain.infrastructure.ledger.SystemIntegrityException;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.autoproxy.BeanNameAutoProxyCreator;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.NameMatchMethodPointcut;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 旧 appendLocal 的 B 保留聚合命名空间隔离门禁（D1 冻结装配）。
 * 机制固定为程序化 Advisor（spring-aop 核心类，不加 aspectjweaver／@AspectJ／新依赖）：
 * BeanNameAutoProxyCreator 仅代理 bean outboxEventRepository，
 * NameMatchMethodPointcut 仅匹配 appendLocal 单方法单 bean。
 * Java 粗过滤快路径（零 DB）＋候选时调用方同事务连接参数化无表只读 SELECT
 * （utf8mb4_0900_ai_ci 等价、零锁副作用、不建读视图）。
 * 设计：docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#721。
 */
@Configuration(proxyBeanMethods = false)
public class OutboxAppendIsolationConfiguration {

    /** B 消费流唯一保留聚合名（31 码点）。 */
    static final String RESERVED_AGGREGATE_NAME = "BUYER_SELECTION_CATALOG_REFRESH";

    /**
     * 冻结参数化无表 SELECT：显式 utf8mb4_0900_ai_ci collation 与源列一致；
     * 首个占位符绑定 aggregateType，保留名以字符串常量嵌入，SQL 文本冻结不变。
     */
    static final String RESERVED_MATCH_SQL =
            "SELECT CAST(? AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_ai_ci\n"
                    + "     = CAST('BUYER_SELECTION_CATALOG_REFRESH' AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_ai_ci\n"
                    + "     AS reserved_match\n";

    /**
     * 零 DB 放行仅可证明安全集合（BLK-1 收窄）：
     * (a) aggregateType == null；(b) 码点计数 &lt; 31（保留名恰 31 个非忽略码点）；
     * (c) 存在任一 ASCII 码点且 ∉ [A-Za-z_]。
     * 快路径只做可靠否定；未命中一律进入候选 SELECT。
     */
    static boolean provablyNotReserved(String aggregateType) {
        if (aggregateType == null) {
            return true;
        }
        int codePoints = aggregateType.codePointCount(0, aggregateType.length());
        if (codePoints < 31) {
            return true;
        }
        for (int i = 0; i < aggregateType.length(); ) {
            int codePoint = aggregateType.codePointAt(i);
            i += Character.charCount(codePoint);
            if (codePoint < 128) {
                if (!((codePoint >= 'A' && codePoint <= 'Z')
                        || (codePoint >= 'a' && codePoint <= 'z')
                        || codePoint == '_')) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean queryReservedMatch(DataSource supplyChainDataSource, String aggregateType) {
        Connection connection = DataSourceUtils.getConnection(supplyChainDataSource);
        try (PreparedStatement statement = connection.prepareStatement(RESERVED_MATCH_SQL)) {
            statement.setString(1, aggregateType);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SystemIntegrityException("B保留聚合命名空间探测未返回结果");
                }
                return resultSet.getBoolean(1);
            }
        } catch (SQLException failure) {
            throw new SystemIntegrityException("B保留聚合命名空间探测失败", failure);
        } finally {
            DataSourceUtils.releaseConnection(connection, supplyChainDataSource);
        }
    }

    /**
     * 生产装配断言：outboxEventRepository 必须已被 appendLocal 隔离 advisor 代理，
     * 装配缺失或目标非代理即启动失败关闭。
     */
    @Bean
    public static OutboxAppendIsolationAssemblyCheck outboxAppendIsolationAssemblyCheck(
            OutboxEventRepository outboxEventRepository) {
        return new OutboxAppendIsolationAssemblyCheck(outboxEventRepository);
    }

    /**
     * appendLocal 保留命名空间隔离 advice（D1 冻结）：粗过滤快路径零 DB；
     * 候选时调用方同事务连接参数化无表只读 SELECT；命中即拒绝，未命中完全透明。
     */
    @Bean
    public static Advisor outboxAppendIsolationAdvisor(
            @Qualifier("supplyChainDataSource") DataSource supplyChainDataSource) {
        NameMatchMethodPointcut pointcut = new NameMatchMethodPointcut();
        pointcut.setMappedName("appendLocal");
        pointcut.setClassFilter(OutboxEventRepository.class::isAssignableFrom);
        MethodInterceptor advice = invocation -> {
            Object[] arguments = invocation.getArguments();
            String aggregateType = arguments.length > 0 ? (String) arguments[0] : null;
            if (provablyNotReserved(aggregateType)) {
                return invocation.proceed();
            }
            if (queryReservedMatch(supplyChainDataSource, aggregateType)) {
                throw new SystemIntegrityException("禁止经旧appendLocal写入B保留聚合命名空间: " + aggregateType);
            }
            return invocation.proceed();
        };
        return new DefaultPointcutAdvisor(pointcut, advice);
    }

    /**
     * 仅代理固定bean outboxEventRepository，并仅安装命名隔离Advisor；
     * 不将其它bean的同名方法纳入门禁，也不把其它Advisor重复应用到全容器。
     * static工厂保证代理创建器尽早注册，避免目标bean在后处理器注册前被初始化。
     */
    @Bean
    public static BeanNameAutoProxyCreator outboxAppendIsolationAutoProxyCreator() {
        BeanNameAutoProxyCreator creator = new BeanNameAutoProxyCreator();
        creator.setBeanNames("outboxEventRepository");
        creator.setInterceptorNames("outboxAppendIsolationAdvisor");
        creator.setProxyTargetClass(true);
        return creator;
    }

}
