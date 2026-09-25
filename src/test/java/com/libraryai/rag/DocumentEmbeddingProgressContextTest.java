package com.libraryai.rag;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentEmbeddingProgressContextTest {

    @Test
    void accumulatesExactGeminiTokensAcrossEmbeddingBatches() {
        List<PdfIngestionProgressEvent> events = new ArrayList<>();
        EmbeddingResponse firstBatch = responseWithTokens(120);
        EmbeddingResponse secondBatch = responseWithTokens(80);

        int tokens = DocumentEmbeddingProgressContext.track(5, events::add, () -> {
            DocumentEmbeddingProgressContext.batchCompleted(3, firstBatch);
            DocumentEmbeddingProgressContext.batchCompleted(2, secondBatch);
        });

        assertThat(tokens).isEqualTo(200);
        assertThat(events).extracting(
                PdfIngestionProgressEvent::completedChunks,
                PdfIngestionProgressEvent::geminiEmbeddingTokens
        ).containsExactly(
                org.assertj.core.groups.Tuple.tuple(3, 120),
                org.assertj.core.groups.Tuple.tuple(5, 200)
        );
    }

    private EmbeddingResponse responseWithTokens(int tokens) {
        EmbeddingResponse response = mock(EmbeddingResponse.class);
        EmbeddingResponseMetadata metadata = mock(EmbeddingResponseMetadata.class);
        Usage usage = mock(Usage.class);
        when(response.getMetadata()).thenReturn(metadata);
        when(metadata.getUsage()).thenReturn(usage);
        when(usage.getTotalTokens()).thenReturn(tokens);
        return response;
    }
}
