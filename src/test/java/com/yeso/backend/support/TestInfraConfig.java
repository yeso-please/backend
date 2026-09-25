package com.yeso.backend.support;

import com.yeso.backend.profile.infrastructure.FakeEmbeddingClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 모든 {@link IntegrationTest}가 공유하는 테스트 인프라. 여기에만 bean을 추가한다 — 테스트 클래스가
 * 자기 설정을 붙이면 Spring context 캐시가 갈라져 컨테이너·context가 늘어난다.
 *
 * <ul>
 *   <li>PostgreSQL 컨테이너 1개(context와 수명을 같이 한다)</li>
 *   <li>외부 API fake: {@link FakeEmbeddingClient}. 새 외부 client fake도 여기에 {@code @Primary}로 추가한다.</li>
 *   <li>{@link MutableClock}: 운영 {@code Clock} bean을 대신한다.</li>
 * </ul>
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestInfraConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("tripin_test")
                .withUsername("tripin_test")
                .withPassword("tripin_test");
    }

    @Bean
    @Primary
    FakeEmbeddingClient fakeEmbeddingClient() {
        return new FakeEmbeddingClient();
    }

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock();
    }

    @Bean
    DatabaseCleaner databaseCleaner(
            JdbcTemplate jdbcTemplate,
            @Value("${spring.jpa.properties.hibernate.default_schema}") String schema) {
        return new DatabaseCleaner(jdbcTemplate, schema);
    }

    @Bean
    ApiFixtures apiFixtures(MockMvc mockMvc) {
        return new ApiFixtures(mockMvc);
    }
}
