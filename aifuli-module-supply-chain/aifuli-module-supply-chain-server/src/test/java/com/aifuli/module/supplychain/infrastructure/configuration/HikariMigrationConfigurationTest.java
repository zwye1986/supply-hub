package com.aifuli.module.supplychain.infrastructure.configuration;

import com.baomidou.dynamic.datasource.creator.hikaricp.HikariDataSourceCreator;
import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.ClassUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/** 零 Druid classpath 与真实 YAML Binder/Creator 选池门禁，见迁移设计 §5.1、§6.1。 */
class HikariMigrationConfigurationTest {
    private static Path root() {
        Path path = Path.of("").toAbsolutePath();
        while (!Files.exists(path.resolve("aifuli-dependencies/pom.xml"))) path = path.getParent();
        return path;
    }

    @Test
    void druidIsAbsentFromRuntimeClasspath() {
        assertThat(ClassUtils.isPresent("com.alibaba.druid.pool.DruidDataSource", getClass().getClassLoader())).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "aifuli-server/src/main/resources/application-local.yaml",
            "aifuli-server/src/main/resources/application-dev.yaml",
            "aifuli-module-system/aifuli-module-system-server/src/main/resources/application-local.yaml",
            "aifuli-module-infra/aifuli-module-infra-server/src/main/resources/application-local.yaml",
            "aifuli-module-member/aifuli-module-member-server/src/main/resources/application-local.yaml"})
    void everyDynamicDataSourceExplicitlyBindsAndCreatesHikari(String file) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        for (var source : new YamlPropertySourceLoader().load("migration", new FileSystemResource(root().resolve(file)))) {
            environment.getPropertySources().addFirst(source);
        }
        var properties = Binder.get(environment).bind("spring.datasource.dynamic", Bindable.of(DynamicDataSourceProperties.class)).get();
        assertThat(properties.getDatasource()).containsKeys("master", "slave");
        var creator = new HikariDataSourceCreator(properties.getHikari());
        for (var entry : properties.getDatasource().entrySet()) {
            var datasource = entry.getValue();
            assertThat(datasource.getType()).as(file + " / " + entry.getKey()).isEqualTo(HikariDataSource.class);
            datasource.setLazy(true); // 不连接配置中的数据库，只验证实际 creator 类型与配置绑定。
            datasource.setPoolName("migration-" + entry.getKey());
            try (HikariDataSource created = (HikariDataSource) creator.createDataSource(datasource)) {
                assertThat(created.getConnectionTimeout()).isEqualTo(properties.getHikari().getConnectionTimeout());
                assertThat(created.getMaximumPoolSize()).isEqualTo(properties.getHikari().getMaxPoolSize());
            }
        }
    }
}
