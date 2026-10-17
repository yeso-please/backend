package com.yeso.backend.profile.application.onboarding;

import com.yeso.backend.auth.infrastructure.UserRepository;
import com.yeso.backend.profile.domain.UserTasteVector;
import com.yeso.backend.profile.infrastructure.EmbeddingProperties;
import com.yeso.backend.profile.infrastructure.OnboardingSubmissionRepository;
import com.yeso.backend.profile.infrastructure.UserTasteVectorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OnboardingQueryServiceTest {
    @Test
    @DisplayName("모델·템플릿·차원이 일치하는 E5 회원 벡터만 반환한다")
    void usesOnlyMatchingE5ModelTemplateAndDimension() {
        var repository = mock(UserTasteVectorRepository.class);
        var properties = new EmbeddingProperties();
        properties.setModelVersion("trained-e5");
        properties.setExpectedDimension(768);
        properties.setProfileTemplateVersion(3);
        var service = new OnboardingQueryService(mock(UserRepository.class),
                mock(OnboardingSubmissionRepository.class),repository,properties);
        var vector = mock(UserTasteVector.class);
        when(repository.findById(1L)).thenReturn(Optional.of(vector));
        when(vector.getModelVersion()).thenReturn("trained-e5");
        when(vector.getDimension()).thenReturn(768);
        when(vector.getTemplateVersion()).thenReturn(3);
        when(vector.getEmbedding()).thenReturn(new byte[768*Float.BYTES]);
        assertThat(service.findTasteVector(1L)).isPresent().get().asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.FLOAT_ARRAY).hasSize(768);
        when(vector.getModelVersion()).thenReturn("old-model");
        assertThat(service.findTasteVector(1L)).isEmpty();
        when(vector.getModelVersion()).thenReturn("trained-e5");
        when(vector.getTemplateVersion()).thenReturn(2);
        assertThat(service.findTasteVector(1L)).isEmpty();
        when(vector.getTemplateVersion()).thenReturn(3);
        when(vector.getDimension()).thenReturn(384);
        assertThat(service.findTasteVector(1L)).isEmpty();
    }
}
