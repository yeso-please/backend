package com.yeso.backend.support;

import com.yeso.backend.profile.infrastructure.FakeEmbeddingClient;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * 모든 통합 테스트({@code *IntegrationTest})의 부모. 설정이 전부 여기 한 곳에 있으므로 Spring context와
 * PostgreSQL 컨테이너는 전체 테스트 실행에서 하나만 뜬다.
 *
 * <p>하위 클래스는 컨테이너, {@code @TestPropertySource}, {@code @Import}, {@code @MockitoBean},
 * {@code @DirtiesContext}, 클래스 레벨 {@code @Transactional}을 붙이지 않는다(docs/conventions/테스트.md).
 * 매 테스트 후 모든 테이블을 TRUNCATE하고 fake client와 시계를 되돌린다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestInfraConfig.class)
public abstract class IntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JsonMapper jsonMapper;

    @Autowired
    protected ApiFixtures fixtures;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected FakeEmbeddingClient fakeEmbeddingClient;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @AfterEach
    void resetSharedState() {
        databaseCleaner.truncateAll();
        fakeEmbeddingClient.reset();
        clock.reset();
    }
}
