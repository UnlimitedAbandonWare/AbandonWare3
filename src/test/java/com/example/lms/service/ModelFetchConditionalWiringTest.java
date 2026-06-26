package com.example.lms.service;

import com.example.lms.repository.ModelEntityRepository;
import com.example.lms.repository.ModelInfoRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ModelFetchConditionalWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ModelFetchService.class, ModelSyncService.class)
            .withBean(ModelInfoRepository.class, () -> mock(ModelInfoRepository.class))
            .withBean(ModelEntityRepository.class, () -> mock(ModelEntityRepository.class));

    @Test
    void disabledPropertyRemovesScheduledModelFetchBeans() {
        contextRunner
                .withPropertyValues("modelfetch.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ModelFetchService.class);
                    assertThat(context).doesNotHaveBean(ModelSyncService.class);
                });
    }

    @Test
    void enabledPropertyRegistersModelFetchBeans() {
        contextRunner
                .withPropertyValues(
                        "modelfetch.enabled=true",
                        "openai.api.key=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ModelFetchService.class);
                    assertThat(context).hasSingleBean(ModelSyncService.class);
                });
    }
}
