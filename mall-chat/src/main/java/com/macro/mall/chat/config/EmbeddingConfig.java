package com.macro.mall.chat.config;

import com.macro.mall.chat.embedding.FakeEmbeddingModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * P0 阶段用确定性 Embedding 验证 VectorStore schema 与过滤语义。
 * P1 接入真实模型（BGE-M3）时替换此 Bean 即可。
 */
@Configuration
public class EmbeddingConfig {

    @Bean
    public EmbeddingModel embeddingModel() {
        return new FakeEmbeddingModel();
    }
}
