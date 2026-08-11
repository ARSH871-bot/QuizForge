package com.quizforge.platform.tenancy;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

@Configuration
public class TenancyConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource pooledDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    /**
     * Wraps the pool so every transaction carries its tenant. Marked
     * {@code @Primary} so JPA, JdbcTemplate and Flyway all receive the wrapper
     * rather than the raw pool.
     *
     * <p>Flyway is unaffected in practice: migrations run with no tenant in
     * scope, so the wrapper hands the connection through untouched and DDL
     * executes as the owner.
     */
    @Bean
    @Primary
    public DataSource dataSource(HikariDataSource pooledDataSource) {
        return new TenantAwareDataSource(pooledDataSource);
    }
}
