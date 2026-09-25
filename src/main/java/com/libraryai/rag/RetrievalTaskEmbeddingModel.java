package com.libraryai.rag;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions.TaskType;

/**
 * Spring AI's VectorStore calls embed(Document) while indexing and embed(String)
 * while searching. This adapter uses that distinction to apply Gemini's
 * RETRIEVAL_DOCUMENT and RETRIEVAL_QUERY modes correctly.
 */
final class RetrievalTaskEmbeddingModel implements EmbeddingModel {

    private final GoogleGenAiTextEmbeddingModel delegate;
    private final GoogleGenAiTextEmbeddingOptions documentOptions;
    private final GoogleGenAiTextEmbeddingOptions queryOptions;
    private final int dimensions;

    RetrievalTaskEmbeddingModel(
            GoogleGenAiTextEmbeddingModel delegate,
            RetrievalProperties properties) {
        this.delegate = delegate;
        this.dimensions = properties.embeddingDimensions();
        this.documentOptions = options(properties, TaskType.RETRIEVAL_DOCUMENT);
        this.queryOptions = options(properties, TaskType.RETRIEVAL_QUERY);
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        // Generic options supplied by VectorStore.add are replaced with the
        // document-specific Gemini options. Explicit Gemini options are honored.
        var options = request.getOptions() instanceof GoogleGenAiTextEmbeddingOptions googleOptions
                ? googleOptions
                : this.documentOptions;
        EmbeddingResponse response = this.delegate.call(
                new EmbeddingRequest(request.getInstructions(), options)
        );
        DocumentEmbeddingProgressContext.batchCompleted(
                request.getInstructions().size(), response
        );
        return response;
    }

    @Override
    public float[] embed(String text) {
        float[] embedding = this.delegate.call(new EmbeddingRequest(List.of(text), this.queryOptions))
                .getResult()
                .getOutput();
        // PgVectorStore performs its SQL search immediately after this method
        // returns, so this is the exact boundary between embedding and search.
        QueryEmbeddingProgressContext.embeddingReady();
        return embedding;
    }

    @Override
    public float[] embed(Document document) {
        String content = this.delegate.getEmbeddingContent(document);
        return this.delegate.call(new EmbeddingRequest(List.of(content), this.documentOptions))
                .getResult()
                .getOutput();
    }

    @Override
    public String getEmbeddingContent(Document document) {
        return this.delegate.getEmbeddingContent(document);
    }

    @Override
    public int dimensions() {
        return this.dimensions;
    }

    private GoogleGenAiTextEmbeddingOptions options(
            RetrievalProperties properties,
            TaskType taskType) {
        return GoogleGenAiTextEmbeddingOptions.builder()
                .model(properties.embeddingModel())
                .taskType(taskType)
                .dimensions(properties.embeddingDimensions())
                .build();
    }
}
