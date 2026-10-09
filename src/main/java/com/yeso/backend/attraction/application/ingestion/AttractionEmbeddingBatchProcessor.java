package com.yeso.backend.attraction.application.ingestion;

import com.yeso.backend.attraction.infrastructure.AttractionEmbeddingBatchRepository;
import com.yeso.backend.attraction.infrastructure.AttractionEmbeddingBatchRepository.Candidate;
import com.yeso.backend.shared.embedding.AttractionEmbeddingBatchClient;
import com.yeso.backend.shared.embedding.AttractionEmbeddingBatchRequest;
import com.yeso.backend.shared.embedding.AttractionEmbeddingBatchRequest.Item;
import com.yeso.backend.shared.embedding.AttractionEmbeddingBatchResponse;
import com.yeso.backend.shared.embedding.AttractionEmbeddingServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 한 번에 한 페이지를 처리한다. AI 장애나 잘못된 응답이면 저장하지 않아 재실행으로 이어진다. */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttractionEmbeddingBatchProcessor {

    static final int ATTRACTION_TEMPLATE_VERSION = 2;

    private final AttractionEmbeddingBatchRepository repository;
    private final AttractionEmbeddingBatchClient embeddingClient;

    @Value("${embedding.model-version:mminilm-l12-ft-b64-v2}")
    private String modelVersion;

    @Value("${embedding.expected-dimension:384}")
    private int expectedDimension;

    @Value("${embedding.attraction-batch.template-version:2}")
    private int templateVersion;

    @Value("${embedding.attraction-batch.batch-size:64}")
    private int batchSize;

    @Transactional
    public int processNextBatch() {
        if (batchSize < 1 || batchSize > 64) {
            throw new IllegalStateException("embedding.attraction-batch.batch-size는 1~64여야 합니다.");
        }
        if (templateVersion != ATTRACTION_TEMPLATE_VERSION) {
            throw new IllegalStateException("현재 AI Hub 계약의 관광지 templateVersion은 2여야 합니다.");
        }
        List<Candidate> candidates = repository.lockNextBatch(
                batchSize, modelVersion, templateVersion, expectedDimension);
        if (candidates.isEmpty()) {
            return 0;
        }

        var request = new AttractionEmbeddingBatchRequest(modelVersion, templateVersion,
                candidates.stream().map(this::toRequestItem).toList());
        AttractionEmbeddingBatchResponse response = embeddingClient.embedAttractions(request);
        Map<Long, byte[]> vectors = validateAndDecode(candidates, response);
        for (Candidate candidate : candidates) {
            repository.saveEmbedding(candidate, vectors.get(candidate.id()), response.dimension(),
                    modelVersion, templateVersion);
        }
        log.info("attraction embeddings batch completed count={} modelVersion={} templateVersion={}",
                candidates.size(), modelVersion, templateVersion);
        return candidates.size();
    }

    private Item toRequestItem(Candidate candidate) {
        List<String> tags = candidate.tags() == null || candidate.tags().isBlank()
                ? List.of()
                : Arrays.stream(candidate.tags().split(",")).map(String::trim).filter(tag -> !tag.isEmpty()).toList();
        return new Item(String.valueOf(candidate.id()), candidate.name(), candidate.contentTypeId(),
                candidate.province() + " " + candidate.city(), tags,
                candidate.description() == null ? "" : candidate.description(),
                candidate.lclsSystm1(), candidate.lclsSystm2(), candidate.lclsSystm3());
    }

    private Map<Long, byte[]> validateAndDecode(List<Candidate> candidates, AttractionEmbeddingBatchResponse response) {
        if (response == null || response.dimension() != expectedDimension || response.items() == null
                || response.items().size() != candidates.size()) {
            throw malformedResponse();
        }
        Set<Long> expectedIds = new HashSet<>();
        candidates.forEach(candidate -> expectedIds.add(candidate.id()));
        Map<Long, byte[]> vectors = new HashMap<>();
        for (AttractionEmbeddingBatchResponse.Item item : response.items()) {
            if (item == null || item.id() == null) {
                throw malformedResponse();
            }
            try {
                Long id = Long.valueOf(item.id());
                if (!expectedIds.contains(id) || item.embeddingBase64() == null
                        || vectors.putIfAbsent(id, Base64.getDecoder().decode(item.embeddingBase64())) != null) {
                    throw malformedResponse();
                }
            } catch (IllegalArgumentException exception) {
                throw malformedResponse();
            }
        }
        if (!vectors.keySet().equals(expectedIds)
                || vectors.values().stream().anyMatch(vector -> vector.length != expectedDimension * Float.BYTES)) {
            throw malformedResponse();
        }
        return vectors;
    }

    private static AttractionEmbeddingServiceException malformedResponse() {
        return new AttractionEmbeddingServiceException("MALFORMED_BATCH_RESPONSE", false, null);
    }
}
