package com.yeso.backend.shared.embedding;

import java.util.List;

public record AttractionEmbeddingBatchResponse(int dimension, List<Item> items) {
    public record Item(String id, String embeddingBase64) {
    }
}
