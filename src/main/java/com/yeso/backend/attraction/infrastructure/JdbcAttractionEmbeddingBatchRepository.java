package com.yeso.backend.attraction.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 추천 품질 필터와 동일한 SQL을 적용하고, 실행 간 중복 처리를 막기 위해 대상 행을 잠근다. */
@Repository
public class JdbcAttractionEmbeddingBatchRepository implements AttractionEmbeddingBatchRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final String schema;
    private final String recommendableSql;

    public JdbcAttractionEmbeddingBatchRepository(
            NamedParameterJdbcTemplate jdbc,
            @Value("${spring.jpa.properties.hibernate.default_schema}") String schema) {
        this.jdbc = jdbc;
        this.schema = schema;
        this.recommendableSql = RegionQualityRepository.RECOMMENDABLE.replace("{h-schema}", schema + ".");
    }

    @Override
    public List<Candidate> lockNextBatch(int limit, String modelVersion, int templateVersion, int dimension) {
        String sql = """
                select a.id, a.name, a.content_type_id, r.province, r.city, a.tags, a.description,
                       a.lcls_systm1, a.lcls_systm2, a.lcls_systm3, a.updated_at
                from %1$s.attractions a
                join %1$s.regions r on r.sig_cd = a.region_id
                where (a.embedding_status <> 'DONE' or not exists (
                    select 1 from %1$s.attraction_embeddings e
                    where e.attraction_id = a.id and e.model_version = :modelVersion
                      and e.template_version = :templateVersion and e.dimension = :dimension
                ))
                  and exists (select 1 from (%2$s) eligible where eligible.id = a.id)
                order by a.id
                limit :limit
                for update of a skip locked
                """.formatted(schema, recommendableSql);
        var params = new MapSqlParameterSource()
                .addValue("limit", limit)
                .addValue("modelVersion", modelVersion)
                .addValue("templateVersion", templateVersion)
                .addValue("dimension", dimension);
        return jdbc.query(sql, params, (rs, row) -> new Candidate(
                rs.getLong("id"), rs.getString("name"), (Integer) rs.getObject("content_type_id"),
                rs.getString("province"), rs.getString("city"), rs.getString("tags"),
                rs.getString("description"), rs.getString("lcls_systm1"), rs.getString("lcls_systm2"),
                rs.getString("lcls_systm3"), rs.getTimestamp("updated_at").toLocalDateTime()));
    }

    @Override
    public void saveEmbedding(Candidate candidate, byte[] vector, int dimension, String modelVersion, int templateVersion) {
        jdbc.update("""
                insert into %1$s.attraction_embeddings
                    (attraction_id, embedding, dimension, model_version, template_version, updated_at)
                values (:id, :embedding, :dimension, :modelVersion, :templateVersion, CURRENT_TIMESTAMP)
                on conflict (attraction_id) do update set
                    embedding = excluded.embedding, dimension = excluded.dimension,
                    model_version = excluded.model_version, template_version = excluded.template_version,
                    updated_at = CURRENT_TIMESTAMP
                """.formatted(schema), new MapSqlParameterSource()
                .addValue("id", candidate.id()).addValue("embedding", vector).addValue("dimension", dimension)
                .addValue("modelVersion", modelVersion).addValue("templateVersion", templateVersion));
        jdbc.update("""
                update %1$s.attractions set embedding_status = 'DONE'
                where id = :id and updated_at = :sourceUpdatedAt and embedding_status <> 'DONE'
                """.formatted(schema), new MapSqlParameterSource()
                .addValue("id", candidate.id()).addValue("sourceUpdatedAt", candidate.updatedAt()));
    }
}
