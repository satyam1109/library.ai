package com.libraryai.rag;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalTaskEmbeddingModelTest {

    @Test
    void usesDocumentModeForDocumentsAndQueryModeForQuestions() {
        GoogleGenAiTextEmbeddingModel delegate = mock(GoogleGenAiTextEmbeddingModel.class);
        when(delegate.getEmbeddingContent(org.mockito.ArgumentMatchers.any(Document.class)))
                .thenAnswer(invocation -> invocation.<Document>getArgument(0).getText());
        when(delegate.call(org.mockito.ArgumentMatchers.any(EmbeddingRequest.class)))
                .thenReturn(new EmbeddingResponse(List.of(new Embedding(new float[] {1.0f, 2.0f}, 0))));
        RetrievalTaskEmbeddingModel model = new RetrievalTaskEmbeddingModel(delegate, properties());

        model.embed(new Document("stored chunk"));
        model.embed("How does HashMap work?");

        ArgumentCaptor<EmbeddingRequest> requests = ArgumentCaptor.forClass(EmbeddingRequest.class);
        verify(delegate, org.mockito.Mockito.times(2)).call(requests.capture());
        List<EmbeddingRequest> values = requests.getAllValues();

        assertThat(options(values.get(0)).getTaskType())
                .isEqualTo(GoogleGenAiTextEmbeddingOptions.TaskType.RETRIEVAL_DOCUMENT);
        assertThat(options(values.get(1)).getTaskType())
                .isEqualTo(GoogleGenAiTextEmbeddingOptions.TaskType.RETRIEVAL_QUERY);
        assertThat(values).allSatisfy(request -> {
            assertThat(options(request).getModel()).isEqualTo("gemini-embedding-001");
            assertThat(options(request).getDimensions()).isEqualTo(768);
        });
    }

    private GoogleGenAiTextEmbeddingOptions options(EmbeddingRequest request) {
        return (GoogleGenAiTextEmbeddingOptions) request.getOptions();
    }

    private RetrievalProperties properties() {
        return new RetrievalProperties(
                "gemini-embedding-001", 768, 5, 20, "public", "library_chunks"
        );
    }
}
