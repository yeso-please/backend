package com.yeso.backend.attraction.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 지도 핀·관광지 상세·지역 카드 조회(docs/api/attraction.md 7-2~7-4). 조건을 붙였다 뗐다 하는 조회라 SQL을 직접 쓴다.
 * 추천 가능 여부는 {@link RegionQualityRepository#RECOMMENDABLE}을 그대로 써서 추첨 판정과 어긋나지 않게 한다.
 * 쇼핑(38)·숙박(32)·음식점(39)은 코스 후보가 아니라서 핀·상세에 나오지 않는다.
 */
@Repository
public class AttractionQueryRepository {

    /** {@code AttractionCategory.fromContentType}과 같은 규칙. 분류코드(#50)를 수집하면 함께 바꾼다. */
    private static final String CATEGORY_SQL =
            "case coalesce(a.content_type_id, 0) when 14 then 'HISTORY_CULTURE' when 28 then 'ACTIVITY' else 'ETC' end";

    private static final String COURSE_TYPE_SQL = "coalesce(a.content_type_id, 12) not in (32, 38, 39)";

    private final NamedParameterJdbcTemplate jdbc;
    private final String schema;
    private final String recommendableSql;

    public AttractionQueryRepository(
            NamedParameterJdbcTemplate jdbc,
            @Value("${spring.jpa.properties.hibernate.default_schema}") String schema) {
        this.jdbc = jdbc;
        this.schema = schema;
        this.recommendableSql = RegionQualityRepository.RECOMMENDABLE.replace("{h-schema}", schema + ".");
    }

    public record PinRow(Long id, String name, Integer contentTypeId, double lat, double lng,
                         String thumbnailUrl, boolean recommendable) {
    }

    /** 지도 영역. null이면 지역 전체다. */
    public record Bounds(double minLng, double minLat, double maxLng, double maxLat) {
    }

    /** {@code afterId}보다 큰 ID부터 {@code limit}개. {@code category}는 {@link #CATEGORY_SQL}의 값이다. */
    public List<PinRow> findPins(String sigCd, Bounds bounds, String category, Long afterId, int limit) {
        StringBuilder sql = new StringBuilder("""
                select a.id, a.name, a.content_type_id, a.lat, a.lng,
                       (select i.image_url from %1$s.attraction_images i
                        where i.attraction_id = a.id and i.validation_status = 'VALID'
                        order by i.display_order, i.id limit 1) as thumbnail_url,
                       exists (select 1 from (%2$s) r where r.id = a.id) as recommendable
                from %1$s.attractions a
                where a.region_id = :sigCd and a.lat is not null and a.lng is not null and %3$s
                """.formatted(schema, recommendableSql, COURSE_TYPE_SQL));
        MapSqlParameterSource params = new MapSqlParameterSource("sigCd", sigCd).addValue("limit", limit);
        if (bounds != null) {
            sql.append(" and a.lng between :minLng and :maxLng and a.lat between :minLat and :maxLat");
            params.addValue("minLng", bounds.minLng()).addValue("maxLng", bounds.maxLng())
                    .addValue("minLat", bounds.minLat()).addValue("maxLat", bounds.maxLat());
        }
        if (category != null) {
            sql.append(" and ").append(CATEGORY_SQL).append(" = :category");
            params.addValue("category", category);
        }
        if (afterId != null) {
            sql.append(" and a.id > :afterId");
            params.addValue("afterId", afterId);
        }
        sql.append(" order by a.id limit :limit");
        return jdbc.query(sql.toString(), params, (rs, n) -> new PinRow(
                rs.getLong("id"), rs.getString("name"), (Integer) rs.getObject("content_type_id"),
                rs.getDouble("lat"), rs.getDouble("lng"), rs.getString("thumbnail_url"), rs.getBoolean("recommendable")));
    }

    public record DetailRow(Long id, String regionSigCd, String name, Integer contentTypeId, String address,
                            Double lat, Double lng, String description, String useTime, String restDate,
                            String sourceSystem, String sourceContentId, LocalDateTime detailFetchedAt,
                            boolean hasValidImage, boolean hasBlockingIssue) {
    }

    public Optional<DetailRow> findDetail(Long attractionId) {
        List<DetailRow> rows = jdbc.query("""
                select a.id, a.region_id, a.name, a.content_type_id, a.addr, a.lat, a.lng, a.description,
                       a.use_time, a.rest_date, a.source_system, a.source_content_id, a.detail_fetched_at,
                       exists (select 1 from %1$s.attraction_images i
                               where i.attraction_id = a.id and i.validation_status = 'VALID') as has_valid_image,
                       exists (select 1 from %1$s.data_quality_issues q
                               where q.entity_type = 'ATTRACTION' and q.entity_key = cast(a.id as varchar)
                                 and q.status = 'OPEN' and q.severity = 'ERROR') as has_blocking_issue
                from %1$s.attractions a
                where a.id = :id and %2$s
                """.formatted(schema, COURSE_TYPE_SQL), new MapSqlParameterSource("id", attractionId),
                (rs, n) -> new DetailRow(
                        rs.getLong("id"), rs.getString("region_id"), rs.getString("name"),
                        (Integer) rs.getObject("content_type_id"), rs.getString("addr"),
                        (Double) rs.getObject("lat"), (Double) rs.getObject("lng"), rs.getString("description"),
                        rs.getString("use_time"), rs.getString("rest_date"), rs.getString("source_system"),
                        rs.getString("source_content_id"),
                        rs.getObject("detail_fetched_at", LocalDateTime.class),
                        rs.getBoolean("has_valid_image"), rs.getBoolean("has_blocking_issue")));
        return rows.stream().findFirst();
    }

    public record ImageRow(String url, String license) {
    }

    /** 검증된(VALID) 이미지만, 표시 순서대로. */
    public List<ImageRow> findValidImages(Long attractionId) {
        return jdbc.query("""
                select i.image_url, i.license_note from %s.attraction_images i
                where i.attraction_id = :id and i.validation_status = 'VALID'
                order by i.display_order, i.id
                """.formatted(schema), new MapSqlParameterSource("id", attractionId),
                (rs, n) -> new ImageRow(rs.getString("image_url"), rs.getString("license_note")));
    }

    public record AttractionViewRow(Long id, String name, Integer contentTypeId, String address, Double lat, Double lng,
                                    String thumbnailUrl, boolean recommendable) {
    }

    /** 코스 화면에 그릴 관광지 정보(현재 데이터 기준). 추천 가능 여부도 함께 본다. */
    public List<AttractionViewRow> findAttractionViews(java.util.Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                select a.id, a.name, a.content_type_id, a.addr, a.lat, a.lng,
                       (select i.image_url from %1$s.attraction_images i
                        where i.attraction_id = a.id and i.validation_status = 'VALID'
                        order by i.display_order, i.id limit 1) as thumbnail_url,
                       exists (select 1 from (%2$s) r where r.id = a.id) as recommendable
                from %1$s.attractions a
                where a.id in (:ids)
                """.formatted(schema, recommendableSql), new MapSqlParameterSource("ids", ids),
                (rs, n) -> new AttractionViewRow(
                        rs.getLong("id"), rs.getString("name"), (Integer) rs.getObject("content_type_id"),
                        rs.getString("addr"), (Double) rs.getObject("lat"), (Double) rs.getObject("lng"),
                        rs.getString("thumbnail_url"), rs.getBoolean("recommendable")));
    }

    public record EmbeddingRow(Long attractionId, byte[] embedding, int dimension) {
    }

    public List<EmbeddingRow> findEmbeddings(java.util.Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                select e.attraction_id, e.embedding, e.dimension from %s.attraction_embeddings e where e.attraction_id in (:ids)
                """.formatted(schema), new MapSqlParameterSource("ids", ids),
                (rs, n) -> new EmbeddingRow(rs.getLong("attraction_id"), rs.getBytes("embedding"), rs.getInt("dimension")));
    }

    public record OfficialCourseStopRow(Long courseId, String title, Long attractionId) {
    }

    /** 지역의 공식 코스 장소(관광지로 연결된 것만), 코스·순서대로. */
    public List<OfficialCourseStopRow> findOfficialCourseStops(String sigCd) {
        return jdbc.query("""
                select c.id as course_id, c.title, s.attraction_id
                from %1$s.official_courses c
                join %1$s.official_course_stops s on s.official_course_id = c.id
                where c.region_id = :sigCd and s.attraction_id is not null
                order by c.id, s.stop_order
                """.formatted(schema), new MapSqlParameterSource("sigCd", sigCd),
                (rs, n) -> new OfficialCourseStopRow(rs.getLong("course_id"), rs.getString("title"), rs.getLong("attraction_id")));
    }

    public record RegionContentRow(String title, String introduction, String historyTags, String heroImageUrl,
                                   String heroImageSourceName, String heroImageSourceUrl, String heroImageLicense,
                                   String heroImageStatus, String characteristicsJson, String landmarksJson,
                                   String sourcesJson, LocalDateTime updatedAt) {
    }

    /** 가장 최근에 승인된 지역 소개문 한 건. */
    public Optional<RegionContentRow> findLatestApprovedContent(String sigCd) {
        List<RegionContentRow> rows = jdbc.query("""
                select c.title, c.introduction, c.history_tags, c.hero_image_url, c.hero_image_source_name,
                       c.hero_image_source_url, c.hero_image_license_note, c.hero_image_validation_status,
                       c.characteristics::text as characteristics, c.landmarks::text as landmarks,
                       c.sources::text as sources, c.updated_at
                from %s.region_contents c
                where c.region_id = :sigCd and c.status = 'APPROVED'
                order by c.reviewed_at desc nulls last, c.id desc
                limit 1
                """.formatted(schema), new MapSqlParameterSource("sigCd", sigCd),
                (rs, n) -> new RegionContentRow(
                        rs.getString("title"), rs.getString("introduction"), rs.getString("history_tags"),
                        rs.getString("hero_image_url"), rs.getString("hero_image_source_name"),
                        rs.getString("hero_image_source_url"), rs.getString("hero_image_license_note"),
                        rs.getString("hero_image_validation_status"), rs.getString("characteristics"),
                        rs.getString("landmarks"), rs.getString("sources"),
                        rs.getObject("updated_at", LocalDateTime.class)));
        return rows.stream().findFirst();
    }
}
