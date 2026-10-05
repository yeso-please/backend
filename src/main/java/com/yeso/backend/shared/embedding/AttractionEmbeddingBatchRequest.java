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
            String description
    ) {
    }
}
