package com.yeso.backend.attraction.application.ranking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 순위 모델 평가기가 LightGBM {@code predict(raw_score=True)}와 같은 점수를 내는지. */
class RankerModelTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static RankerModel load(InputStream input) throws Exception {
        try (input) {
            return RankerModel.read(input);
        }
    }

    private static void assertParity(RankerModel model, JsonNode expected) {
        JsonNode rows = expected.get("rows");
        JsonNode scores = expected.get("scores");
        assertThat(rows.size()).isEqualTo(scores.size()).isPositive();
        for (int r = 0; r < rows.size(); r++) {
            double[] x = new double[rows.get(r).size()];
            for (int i = 0; i < x.length; i++) {
                JsonNode value = rows.get(r).get(i);
                x[i] = value.isNull() ? Double.NaN : value.asDouble();
            }
            assertThat(model.score(x)).as("row %d", r).isCloseTo(scores.get(r).asDouble(), within(1e-9));
        }
    }

    @Test
    @DisplayName("합성 모델: 수치·범주 분할, 결측, 학습에 없던 범주까지 LightGBM과 같은 점수")
    void syntheticParity() throws Exception {
        RankerModel model = load(getClass().getResourceAsStream("/ranker/synthetic-ranker.json"));
        assertThat(model.version()).isEqualTo("synthetic-v1");
        assertThat(model.features()).containsExactly("e5_cos", "style_1", "desc_chars", "contenttypeid");
        assertThat(model.categoryCode("contenttypeid", "2")).isEqualTo(2);
        assertThat(model.categoryCode("contenttypeid", "99")).isEqualTo(-1);
        assertParity(model, JSON.readTree(getClass().getResourceAsStream("/ranker/synthetic-expected.json")));
    }

    @Test
    @DisplayName("실제 모델(로컬에 있을 때만): ai가 남긴 특성 행 200개와 같은 점수")
    void realModelParity() throws Exception {
        String dir = System.getenv().getOrDefault("RANKER_PARITY_DIR", "");
        assumeTrue(!dir.isBlank() && Files.exists(Path.of(dir, "ranker.json")), "RANKER_PARITY_DIR not set");
        RankerModel model = load(Files.newInputStream(Path.of(dir, "ranker.json")));
        assertParity(model, JSON.readTree(Files.readString(Path.of(dir, "parity_rows.json"))));
    }

    @Test
    @DisplayName("설문 값은 학습과 같이 바꾼다: 스타일 (값−4)/3, 답이 없으면 0, 동기는 고른 것만 1")
    void surveyValues() {
        double[] values = TasteRanker.surveyValues(new TasteRanker.Survey(Map.of(1, 7, 3, 1, 5, 4), List.of(2, 9, 12)));
        assertThat(values).hasSize(13);
        assertThat(values[0]).isEqualTo(1.0);
        assertThat(values[1]).isEqualTo(-1.0);
        assertThat(values[2]).isEqualTo(0.0);
        assertThat(values[3]).isEqualTo(0.0);   // 스타일 6 답 없음
        assertThat(values[4 + 1]).isEqualTo(1.0);
        assertThat(values[4 + 8]).isEqualTo(1.0);
        assertThat(values[4]).isEqualTo(0.0);
    }
}
