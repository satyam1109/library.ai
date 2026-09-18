package com.libraryai.rag;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Keeps one Gemini embedding model while selecting the correct asymmetric
 * retrieval task for documents and questions.
 */
@Configuration
@EnableConfigurationProperties(RetrievalProperties.class)
public class RetrievalEmbeddingConfiguration {

    @Bean
    @Primary
    EmbeddingModel retrievalTaskEmbeddingModel(
            GoogleGenAiTextEmbeddingModel googleEmbeddingModel,
            RetrievalProperties properties) {
        return new RetrievalTaskEmbeddingModel(googleEmbeddingModel, properties);
    }
}
