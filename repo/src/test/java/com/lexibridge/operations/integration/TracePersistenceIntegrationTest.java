package com.lexibridge.operations.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.task.scheduling.enabled=false",
    "lexibridge.security.antivirus.enabled=false"
})
class TracePersistenceIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("lexibridge")
        .withUsername("lexibridge")
        .withPassword("lexibridge")
        .withCommand("--log-bin-trust-function-creators=1");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void healthRequest_shouldEmitAndPersistTrace() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);
        assertEquals(200, response.getStatusCode().value());
        String traceId = response.getHeaders().getFirst("X-Trace-Id");
        assertNotNull(traceId);

        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from trace_event where trace_id = ? and path = '/actuator/health'",
            Integer.class,
            traceId
        );
        assertNotNull(count);
        assertTrue(count > 0);
    }
}
