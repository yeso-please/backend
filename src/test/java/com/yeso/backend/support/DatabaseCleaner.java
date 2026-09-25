package com.yeso.backend.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 앱 스키마의 모든 테이블을 한 번의 {@code TRUNCATE ... RESTART IDENTITY CASCADE}로 비운다.
 * {@code flyway_schema_history}만 남긴다. V1~V6 migration은 참조 데이터를 INSERT하지 않으므로
 * 지워서 안 되는 seed 테이블은 없다 — migration이 seed를 넣게 되면 여기 제외 목록에 추가한다.
 * 테이블 목록은 information_schema에서 한 번 읽어 캐시한다(새 migration은 새 context에서만 생긴다).
 */
public class DatabaseCleaner {

    private static final List<String> EXCLUDED_TABLES = List.of("flyway_schema_history");

    private final JdbcTemplate jdbcTemplate;
    private final String schema;
    private volatile String truncateSql;

    public DatabaseCleaner(JdbcTemplate jdbcTemplate, String schema) {
        this.jdbcTemplate = jdbcTemplate;
        this.schema = schema;
    }

    public void truncateAll() {
        jdbcTemplate.execute(truncateSql());
    }

    private String truncateSql() {
        if (truncateSql == null) {
            List<String> tables = jdbcTemplate.queryForList("""
                    SELECT table_name
                    FROM information_schema.tables
                    WHERE table_schema = ? AND table_type = 'BASE TABLE'
                    ORDER BY table_name
                    """, String.class, schema).stream()
                    .filter(table -> !EXCLUDED_TABLES.contains(table))
                    .toList();
            if (tables.isEmpty()) {
                throw new IllegalStateException("스키마 '" + schema + "'에 비울 테이블이 없습니다.");
            }
            truncateSql = tables.stream()
                    .map(table -> "\"" + schema + "\".\"" + table + "\"")
                    .collect(Collectors.joining(", ", "TRUNCATE TABLE ", " RESTART IDENTITY CASCADE"));
        }
        return truncateSql;
    }
}
