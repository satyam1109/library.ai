package com.libraryai.rag;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SimilarityRetrievalServiceTest {

    private static final String DOCUMENT_ID = "123e4567-e89b-12d3-a456-426614174000";

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final SimilarityRetrievalService service = new SimilarityRetrievalService(
            vectorStore,
            new RetrievalProperties("gemini-embedding-001", 768, 5, 20, "public", "library_chunks")
    );

    @Test
    void returnsOriginalTextMetadataAndSimilarityScore() {
        Document match = Document.builder()
                .text("HashMap stores entries in buckets.")
                .metadata(Map.of("section_title", "18. HashMap Internals"))
                .score(0.91)
                .build();
        when(vectorStore.similaritySearch(org.mockito.ArgumentMatchers.any(SearchRequest.class)))
                .thenReturn(List.of(match));

        SimilaritySearchResponse response = service.search(
                new SimilaritySearchRequest("How does HashMap work?", 3, DOCUMENT_ID)
        );

        assertThat(response.documentId()).isEqualTo(DOCUMENT_ID);
        assertThat(response.topK()).isEqualTo(3);
        assertThat(response.results()).singleElement().satisfies(result -> {
            assertThat(result.score()).isEqualTo(0.91);
            assertThat(result.text()).contains("buckets");
            assertThat(result.metadata()).containsEntry("section_title", "18. HashMap Internals");
        });

        ArgumentCaptor<SearchRequest> search = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(search.capture());
        assertThat(search.getValue().getQuery()).isEqualTo("How does HashMap work?");
        assertThat(search.getValue().getTopK()).isEqualTo(3);
        assertThat(search.getValue().getFilterExpression()).isNotNull();
    }

    @Test
    void rejectsOutOfRangeTopK() {
        assertThatThrownBy(() -> service.search(
                new SimilaritySearchRequest("question", 21, DOCUMENT_ID)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresAValidDocumentId() {
        assertThatThrownBy(() -> service.search(
                new SimilaritySearchRequest("question", 5, null)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("documentId");
    }

    @Test
    void searchesSelectedDocumentsInOneGloballyRankedVectorQuery() {
        String secondDocumentId = "223e4567-e89b-12d3-a456-426614174000";
        when(vectorStore.similaritySearch(org.mockito.ArgumentMatchers.any(SearchRequest.class)))
                .thenReturn(List.of());

        MultiDocumentSimilaritySearchResponse response = service.searchAcrossDocuments(
                "Compare their leave policies",
                List.of(DOCUMENT_ID, secondDocumentId),
                5
        );

        assertThat(response.documentIds()).containsExactly(DOCUMENT_ID, secondDocumentId);
        ArgumentCaptor<SearchRequest> search = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(search.capture());
        assertThat(search.getValue().getTopK()).isEqualTo(5);
        assertThat(search.getValue().getFilterExpression().toString())
                .contains("document_id")
                .contains(DOCUMENT_ID)
                .contains(secondDocumentId);
    }

    @Test
    void rejectsMoreThanThreeDocuments() {
        assertThatThrownBy(() -> service.searchAcrossDocuments(
                "question",
                List.of(
                        DOCUMENT_ID,
                        "223e4567-e89b-12d3-a456-426614174000",
                        "323e4567-e89b-12d3-a456-426614174000",
                        "423e4567-e89b-12d3-a456-426614174000"
                ),
                5
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 3");
    }
}
