package com.yeso.backend.attraction.application.region;

import com.yeso.backend.attraction.infrastructure.AttractionQueryRepository;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

/**
 * 지역 한 줄 소개 생성(#89)의 근거: 대표 관광지와 지역 관광지 분류 분포.
 * 모듈 밖의 생성 도구(SummaryBackfill)가 attraction infrastructure를 직접 쓰지 않도록 여기서 감싼다.
 */
public final class RegionSummaryMaterial {

    /** 대표 관광지. 순서는 7-2 대표 사진과 같다(추천코스 지점 빈도 → 설명 길이 → ID). */
    public record Representative(Long id, String name, String lclsSystm1, String lclsSystm2, String lclsSystm3,
                                 String description) {
    }

    private final AttractionQueryRepository repository;

    private RegionSummaryMaterial(AttractionQueryRepository repository) {
        this.repository = repository;
    }

    /** 배치 도구용. 호출자가 연 연결을 그대로 쓰고 닫지 않는다(쓰기 잠금과 같은 연결). */
    public static RegionSummaryMaterial forConnection(Connection connection, String schema) {
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(new SingleConnectionDataSource(connection, true));
        // 임베딩 설정은 이 조회에 쓰지 않는다.
        return new RegionSummaryMaterial(new AttractionQueryRepository(jdbc, schema, "unused", 2, 384));
    }

    public List<Representative> representatives(String sigCd, int limit) {
        return repository.findRepresentativeAttractions(sigCd, limit).stream()
                .map(row -> new Representative(row.id(), row.name(), row.lclsSystm1(), row.lclsSystm2(), row.lclsSystm3(),
                        row.description()))
                .toList();
    }

    public Map<String, Integer> classCounts(String sigCd) {
        return repository.countClassCodes(sigCd);
    }
}
