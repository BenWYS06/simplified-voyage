package com.ben.services.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;

/**
 * A real MySQL in Docker for the tests (same image as docker-compose). @ServiceConnection points the
 * datasource at it, so Flyway runs the real migrations and Hibernate validates the real schema.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    @Bean
    @ServiceConnection
    MySQLContainer database() {
        return new MySQLContainer("mysql:8.0");
    }
}
