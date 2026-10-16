package com.yeso.backend.trip.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** 지역 × 성향 맞춤 문장 캐시(region_match_texts, 7-6). 근거 해시가 같으면 다시 만들지 않는다. */
@Repository
public class RegionMatchTextRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final String schema;

    public RegionMatchTextRepository(
            NamedParameterJdbcTemplate jdbc, @Value("${spring.jpa.properties.hibernate.default_schema}") String schema) {
        this.jdbc = jdbc;
        this.schema = schema;
    }

    public record Cached(String text, String textSource, String sourceHash) {
    }

    public Optional<Cached> find(String sigCd, String mbtiCode) {
        return jdbc.query("""
                select text, text_source, source_hash from %s.region_match_texts
                where region_id = :sigCd and mbti_code = :mbti
                """.formatted(schema), new MapSqlParameterSource("sigCd", sigCd).addValue("mbti", mbtiCode),
                (rs, n) -> new Cached(rs.getString("text"), rs.getString("text_source"), rs.getString("source_hash")))
                .stream().findFirst();
    }

    public void save(String sigCd, String mbtiCode, String text, String textSource, String sourceHash,
                     String generatorModel, String promptVersion) {
        jdbc.update("""
                insert into %s.region_match_texts
                    (region_id, mbti_code, text, text_source, source_hash, generator_model, prompt_version)
                values (:sigCd, :mbti, :text, :source, :hash, :model, :prompt)
                on conflict (region_id, mbti_code) do update set
                    text = excluded.text, text_source = excluded.text_source, source_hash = excluded.source_hash,
                    generator_model = excluded.generator_model, prompt_version = excluded.prompt_version,
                    created_at = current_timestamp
                """.formatted(schema), new MapSqlParameterSource("sigCd", sigCd).addValue("mbti", mbtiCode)
                .addValue("text", text).addValue("source", textSource).addValue("hash", sourceHash)
                .addValue("model", generatorModel).addValue("prompt", promptVersion));
    }
}
