package com.yeso.backend.trip.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** 추천 노출 기록(recommendation_impressions). 쓰기만 한다. 분석은 오프라인에서 한다. */
@Repository
public class RecommendationImpressionRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final String schema;

    public RecommendationImpressionRepository(
            NamedParameterJdbcTemplate jdbc, @Value("${spring.jpa.properties.hibernate.default_schema}") String schema) {
        this.jdbc = jdbc;
        this.schema = schema;
    }

    public void insert(UUID requestId, Long userId, Long tripPlanId, String stage, String policy, String appliedConditionsJson,
                       String modelVersion, Integer templateVersion, String rankerVersion, String candidatesJson,
                       String chosenJson) {
        jdbc.update("""
                insert into %s.recommendation_impressions
                    (request_id, user_id, trip_plan_id, stage, policy, applied_conditions, model_version, template_version,
                     ranker_version, candidates, chosen)
                values (:requestId, :userId, :tripId, :stage, :policy, cast(:applied as jsonb), :model, :template,
                        :ranker, cast(:candidates as jsonb), cast(:chosen as jsonb))
                """.formatted(schema), new MapSqlParameterSource("requestId", requestId)
                .addValue("userId", userId).addValue("tripId", tripPlanId).addValue("stage", stage)
                .addValue("policy", policy).addValue("applied", appliedConditionsJson).addValue("model", modelVersion)
                .addValue("template", templateVersion).addValue("ranker", rankerVersion)
                .addValue("candidates", candidatesJson).addValue("chosen", chosenJson));
    }
}
