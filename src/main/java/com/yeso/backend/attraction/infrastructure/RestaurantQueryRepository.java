package com.yeso.backend.attraction.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/** #52가 채우는 식당과 출처를 5-5 추천용으로 읽는다. */
@Repository
public class RestaurantQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final String schema;

    public RestaurantQueryRepository(NamedParameterJdbcTemplate jdbc,
                                     @Value("${spring.jpa.properties.hibernate.default_schema}") String schema) {
        this.jdbc = jdbc;
        this.schema = schema;
    }

    public record Row(Long restaurantId, String externalId, String name, String category,
                      String address, String roadAddress, String phone, double lat, double lng,
                      String placeUrl, String imageUrl, String representativeMenu,
                      String sourceName, String sourceUrl, LocalDateTime fetchedAt) {
    }

    public List<Row> findTourApi(String sigCd) {
        return jdbc.query("""
                select distinct on (r.id) r.id, s.external_id, r.name, r.category, r.address,
                       r.road_address, r.phone, r.lat, r.lng, r.place_url,
                       case when r.image_validation_status = 'VALID' then r.image_url end as image_url,
                       r.representative_menu, s.source_name, s.source_url, s.fetched_at
                from %1$s.restaurants r
                join %1$s.restaurant_sources s on s.restaurant_id = r.id
                where r.region_id = :sigCd and s.provider = 'TOUR_API' and s.content_type_id = 39
                order by r.id, s.fetched_at desc, s.id desc
                """.formatted(schema), new MapSqlParameterSource("sigCd", sigCd), (rs, rowNum) -> new Row(
                rs.getLong("id"), rs.getString("external_id"), rs.getString("name"), rs.getString("category"),
                rs.getString("address"), rs.getString("road_address"), rs.getString("phone"),
                rs.getDouble("lat"), rs.getDouble("lng"), rs.getString("place_url"), rs.getString("image_url"),
                rs.getString("representative_menu"), rs.getString("source_name"), rs.getString("source_url"),
                rs.getObject("fetched_at", LocalDateTime.class)));
    }
}
