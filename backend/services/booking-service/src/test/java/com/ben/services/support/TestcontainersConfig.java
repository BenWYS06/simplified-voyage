package com.ben.services.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A real PostgreSQL in Docker for the tests (same image as docker-compose). @ServiceConnection points the
 * datasource at it, so Flyway runs the real migrations and Hibernate validates the real schema.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer database() {
        return new PostgreSQLContainer("postgres:16-alpine");
    }
}
