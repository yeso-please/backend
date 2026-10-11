package com.yeso.backend.shared.embedding;

import java.util.List;

public record AttractionEmbeddingBatchRequest(
        String modelVersion,
        int templateVersion,
        List<Item> items
) {
    public record Item(
            String id,
            String name,
            Integer contentTypeId,
            String regionName,
            List<String> tags,
            String description,
            // TourAPI 새 분류체계 코드(V19). ai가 분류명으로 바꿔 관광지 문장에 넣는다. 없으면 null
            String lclsSystm1,
            String lclsSystm2,
            String lclsSystm3
    ) {
    }
}
