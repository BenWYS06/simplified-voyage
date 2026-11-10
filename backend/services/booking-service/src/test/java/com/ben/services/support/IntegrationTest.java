package com.ben.services.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Base for tests that need the real database (Docker must be running). Only the database is real: no
 * config-server, no service registry, and the scheduled jobs are off so the tests call them directly.
 */
@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "app.scheduling.enabled=false"
})
@Import(TestcontainersConfig.class)
public abstract class IntegrationTest {
}
