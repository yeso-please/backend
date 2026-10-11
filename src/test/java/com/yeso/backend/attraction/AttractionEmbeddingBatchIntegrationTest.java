package com.yeso.backend.attraction;

import com.yeso.backend.attraction.application.ingestion.AttractionEmbeddingBatchProcessor;
import com.yeso.backend.attraction.domain.Region;
import com.yeso.backend.attraction.infrastructure.RegionRepository;
import com.yeso.backend.profile.infrastructure.FakeEmbeddingClient;
import com.yeso.backend.shared.embedding.AttractionEmbeddingServiceException;
import com.yeso.backend.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttractionEmbeddingBatchIntegrationTest extends IntegrationTest {

    @Autowired
    private RegionRepository regionRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AttractionEmbeddingBatchProcessor processor;

    @BeforeEach
    void seedRegion() {
        regionRepository.save(new Region("11110", "서울특별시", "종로구"));
    }

    @Nested
    @DisplayName("관광지 임베딩 배치")
    class Process {

        @Test
        @DisplayName("추천 가능한 PENDING 관광지를 저장하고 재실행에서는 건너뛴다")
        void processNextBatch_success_isIdempotent() {
            Long attractionId = insertRecommendableAttraction("경복궁", "조선의 법궁", "궁궐,역사");

            assertThat(processor.processNextBatch()).isEqualTo(1);
            assertThat(fakeEmbeddingClient.lastBatchRequest().modelVersion()).isEqualTo("mminilm-l12-v1");
            assertThat(fakeEmbeddingClient.lastBatchRequest().templateVersion()).isEqualTo(2);
            assertThat(fakeEmbeddingClient.lastBatchRequest().items()).singleElement()
                    .satisfies(item -> assertThat(item)
                            .extracting("id", "name", "regionName", "description")
                            .containsExactly(String.valueOf(attractionId), "경복궁", "서울특별시 종로구", "조선의 법궁"));
            assertThat(jdbc.queryForObject("select embedding_status from app.attractions where id = ?", String.class, attractionId))
                    .isEqualTo("DONE");
            assertThat(jdbc.queryForObject("select count(*) from app.attraction_embeddings where attraction_id = ?", Integer.class, attractionId))
                    .isEqualTo(1);
            assertThat(processor.processNextBatch()).isZero();
        }

        @Test
        @DisplayName("분류코드(lcls_systm1~3)를 요청에 담는다. 없으면 null이다")
        void processNextBatch_sendsClassificationCodes() {
            Long classified = insertRecommendableAttraction("경복궁", "조선의 법궁", "");
            Long unclassified = insertRecommendableAttraction("창덕궁", "조선의 궁궐", "");
            jdbc.update("update app.attractions set lcls_systm1 = 'HS', lcls_systm2 = 'HS01', lcls_systm3 = 'HS010100' where id = ?", classified);

            assertThat(processor.processNextBatch()).isEqualTo(2);
            assertThat(fakeEmbeddingClient.lastBatchRequest().items())
                    .extracting("id", "lclsSystm1", "lclsSystm2", "lclsSystm3")
                    .containsExactly(
                            tuple(String.valueOf(classified), "HS", "HS01", "HS010100"),
                            tuple(String.valueOf(unclassified), null, null, null));
        }

        @Test
        @DisplayName("임베딩 서비스가 실패하면 벡터를 저장하지 않아 다음 실행에서 이어간다")
        void processNextBatch_serviceFailure_leavesCandidatePending() {
            Long attractionId = insertRecommendableAttraction("창덕궁", "후원으로 유명한 궁궐", "궁궐");
            fakeEmbeddingClient.setMode(FakeEmbeddingClient.Mode.TRANSIENT);

            assertThatThrownBy(() -> processor.processNextBatch())
                    .isInstanceOf(AttractionEmbeddingServiceException.class);
            assertThat(jdbc.queryForObject("select embedding_status from app.attractions where id = ?", String.class, attractionId))
                    .isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("select count(*) from app.attraction_embeddings where attraction_id = ?", Integer.class, attractionId))
                    .isZero();
        }

        @Test
        @DisplayName("AI가 기대 차원과 다른 벡터를 반환하면 해당 묶음을 저장하지 않는다")
        void processNextBatch_dimensionMismatch_rollsBackBatch() {
            Long attractionId = insertRecommendableAttraction("덕수궁", "대한제국 역사 공간", "궁궐,역사");
            fakeEmbeddingClient.setMode(FakeEmbeddingClient.Mode.DIMENSION_MISMATCH);

            assertThatThrownBy(() -> processor.processNextBatch())
                    .isInstanceOf(AttractionEmbeddingServiceException.class);
            assertThat(jdbc.queryForObject("select embedding_status from app.attractions where id = ?", String.class, attractionId))
                    .isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("select count(*) from app.attraction_embeddings where attraction_id = ?", Integer.class, attractionId))
                    .isZero();
        }
    }

    private Long insertRecommendableAttraction(String name, String description, String tags) {
        Long id = jdbc.queryForObject("""
                insert into app.attractions
                    (name, category, region_id, description, tags, lat, lng, content_type_id, source_content_id)
                values (?, 'HISTORY_CULTURE', '11110', ?, ?, 37.57, 126.97, 14, ?)
                returning id
                """, Long.class, name, description, tags, "test-" + name);
        jdbc.update("""
                insert into app.attraction_images (attraction_id, image_url, validation_status)
                values (?, ?, 'VALID')
                """, id, "https://example.test/" + id + ".jpg");
        return id;
    }
}
