package com.yeso.backend.attraction.infrastructure;

import com.yeso.backend.attraction.domain.Region;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 추첨 가능 지역·추천 가능 관광지 판정에 쓰는 조회(docs/api/attraction.md 7장 머리).
 * 추천 가능 관광지 조건은 {@link #RECOMMENDABLE} 한 곳에만 두고 지역 판정과 코스 후보 조회가 같이 쓴다.
 */
public interface RegionQualityRepository extends Repository<Region, String> {

    /**
     * 추천 가능 관광지: 좌표, 공백 아닌 설명, 검증된(VALID) 이미지 1장 이상, 열린 ERROR 품질 이슈 없음.
     * 쇼핑(38)·숙박(32)·음식점(39)과 분류가 캠핑(AC05)인 곳은 코스 후보가 아니다.
     */
    String RECOMMENDABLE = """
            select a.* from {h-schema}attractions a
            where a.lat is not null and a.lng is not null
              and a.description is not null and btrim(a.description) <> ''
              and coalesce(a.content_type_id, 12) not in (32, 38, 39)
              and coalesce(a.lcls_systm2, '') <> 'AC05'
              and exists (select 1 from {h-schema}attraction_images i
                          where i.attraction_id = a.id and i.validation_status = 'VALID')
              and not exists (select 1 from {h-schema}data_quality_issues q
                              where q.entity_type = 'ATTRACTION' and q.entity_key = cast(a.id as varchar)
                                and q.status = 'OPEN' and q.severity = 'ERROR')
            """;

    /** 지역별 판정 재료. 소개문은 가장 최근에 승인된 한 건을 본다. */
    String REGION_QUALITY = """
            select r.sig_cd as sigCd, r.province as province, r.city as city, r.lat as lat, r.lng as lng,
                   (c.id is not null) as hasApprovedContent,
                   coalesce(c.hero_image_validation_status = 'VALID', false) as hasValidHeroImage,
                   (select count(*) from (""" + RECOMMENDABLE + """
                   ) ra where ra.region_id = r.sig_cd) as recommendableCount
            from {h-schema}regions r
            left join lateral (
                select rc.id, rc.hero_image_validation_status from {h-schema}region_contents rc
                where rc.region_id = r.sig_cd and rc.status = 'APPROVED'
                order by rc.reviewed_at desc nulls last, rc.id desc
                limit 1
            ) c on true
            """;

    interface RegionQualityRow {
        String getSigCd();

        String getProvince();

        String getCity();

        Double getLat();

        Double getLng();

        boolean getHasApprovedContent();

        boolean getHasValidHeroImage();

        long getRecommendableCount();
    }

    interface RecommendableAttractionRow {
        Long getId();

        String getName();

        Integer getContentTypeId();

        String getAddr();

        Double getLat();

        Double getLng();

        String getThumbnailUrl();
    }

    @Query(value = REGION_QUALITY + " order by r.sig_cd", nativeQuery = true)
    List<RegionQualityRow> findAllQuality();

    @Query(value = REGION_QUALITY + " where r.sig_cd = :sigCd", nativeQuery = true)
    Optional<RegionQualityRow> findQuality(@Param("sigCd") String sigCd);

    @Query(value = """
            select ra.id as id, ra.name as name, ra.content_type_id as contentTypeId, ra.addr as addr,
                   ra.lat as lat, ra.lng as lng,
                   (select i.image_url from {h-schema}attraction_images i
                    where i.attraction_id = ra.id and i.validation_status = 'VALID'
                    order by i.display_order, i.id limit 1) as thumbnailUrl
            from (""" + RECOMMENDABLE + """
            ) ra
            where ra.region_id = :sigCd
            order by ra.id
            """, nativeQuery = true)
    List<RecommendableAttractionRow> findRecommendableAttractions(@Param("sigCd") String sigCd);
}
