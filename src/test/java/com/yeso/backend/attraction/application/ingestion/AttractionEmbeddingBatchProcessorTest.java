package com.yeso.backend.attraction.application.ingestion;

import com.yeso.backend.attraction.infrastructure.AttractionEmbeddingBatchRepository;
import com.yeso.backend.attraction.infrastructure.AttractionEmbeddingBatchRepository.Candidate;
import com.yeso.backend.shared.embedding.AttractionEmbeddingBatchClient;
import com.yeso.backend.shared.embedding.AttractionEmbeddingBatchRequest;
import com.yeso.backend.shared.embedding.AttractionEmbeddingBatchResponse;
import com.yeso.backend.shared.embedding.AttractionEmbeddingServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttractionEmbeddingBatchProcessorTest {
    @Test
    @DisplayName("E5 관광지 입력을 전달하고 차원이 다른 응답은 저장하지 않는다")
    void e5BatchForwardsClassificationAddressAnd768Dimensions() {
        var repository = mock(AttractionEmbeddingBatchRepository.class);
        var client = mock(AttractionEmbeddingBatchClient.class);
        var processor = new AttractionEmbeddingBatchProcessor(repository, client);
        ReflectionTestUtils.setField(processor,"modelVersion","trained-e5");
        ReflectionTestUtils.setField(processor,"expectedDimension",768);
        ReflectionTestUtils.setField(processor,"templateVersion",3);
        ReflectionTestUtils.setField(processor,"batchSize",8);
        var candidate = new Candidate(1L,"화성행궁",12,"경기도","수원시","역사","궁궐",
                LocalDateTime.of(2026,10,17,0,0),"HS","HS01","HS010100","경기도 수원시 팔달구 정조로 825");
        when(repository.lockNextBatch(8,"trained-e5",3,768)).thenReturn(List.of(candidate));
        byte[] bytes = new byte[768*Float.BYTES];
        when(client.embedAttractions(any())).thenReturn(new AttractionEmbeddingBatchResponse(768,
                List.of(new AttractionEmbeddingBatchResponse.Item("1",Base64.getEncoder().encodeToString(bytes)))));
        assertThat(processor.processNextBatch()).isEqualTo(1);
        var request = ArgumentCaptor.forClass(AttractionEmbeddingBatchRequest.class);
        verify(client).embedAttractions(request.capture());
        assertThat(request.getValue().items()).singleElement().satisfies(item -> {
            assertThat(item.regionName()).isEqualTo("경기도 수원시 팔달구");
            assertThat(item.lclsSystm1()).isEqualTo("HS");
            assertThat(item.lclsSystm2()).isEqualTo("HS01");
            assertThat(item.lclsSystm3()).isEqualTo("HS010100");
        });
        verify(repository).saveEmbedding(candidate, bytes,768,"trained-e5",3);
        when(client.embedAttractions(any())).thenReturn(new AttractionEmbeddingBatchResponse(384,List.of()));
        assertThatThrownBy(processor::processNextBatch).isInstanceOf(AttractionEmbeddingServiceException.class);
        verify(repository, times(1)).saveEmbedding(any(),any(),anyInt(),anyString(),anyInt());
    }
}
