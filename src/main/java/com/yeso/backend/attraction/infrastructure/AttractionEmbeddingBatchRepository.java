package com.yeso.backend.attraction.infrastructure;

import java.time.LocalDateTime;
import java.util.List;

/** 배치 한 번에 필요한 추천 가능 관광지를 잠그고 저장한다. */
public interface AttractionEmbeddingBatchRepository {

    record Candidate(
            Long id,
            String name,
            Integer contentTypeId,
            String province,
            String city,
            String tags,
            String description,
            LocalDateTime updatedAt
    ) {
    }

    List<Candidate> lockNextBatch(int limit, String modelVersion, int templateVersion, int dimension);

    void saveEmbedding(Candidate candidate, byte[] vector, int dimension, String modelVersion, int templateVersion);
}
