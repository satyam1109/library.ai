package com.libraryai.rag;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HybridRetrievalServiceTest {

    private static final String DOCUMENT_ID = "123e4567-e89b-12d3-a456-426614174000";

    private final SimilarityRetrievalService vectorRetrieval = mock(SimilarityRetrievalService.class);
    private final ElasticsearchChunkIndexService keywordRetrieval =
            mock(ElasticsearchChunkIndexService.class);
    private final HybridRetrievalService service = new HybridRetrievalService(
            vectorRetrieval,
            keywordRetrieval,
            new RetrievalProperties("gemini-embedding-001", 768, 5, 20, "public", "library_chunks"),
            new ElasticsearchProperties(true, "http://localhost:9200", "test-chunks", 20, 60, 0.60)
    );

    @Test
    void reciprocalRankFusionRewardsChunksFoundByBothSearches() {
        List<SimilaritySearchResult> vectors = List.of(
                vector("A", 1, 0.91, "Semantic A"),
                vector("B", 2, 0.88, "Semantic B"),
                vector("C", 3, 0.85, "Semantic C")
        );
        when(vectorRetrieval.searchAcrossDocuments(
                "older context plus question", List.of(DOCUMENT_ID), 20
        ))
                .thenReturn(response(vectors));
        when(keywordRetrieval.search("question", List.of(DOCUMENT_ID), 20)).thenReturn(List.of(
                keyword("C", 9.2, "Semantic C", 3),
                keyword("A", 8.7, "Semantic A", 1),
                keyword("F", 7.4, "Keyword-only F", 9)
        ));

        MultiDocumentSimilaritySearchResponse result = service.searchAcrossDocuments(
                "older context plus question", "question", List.of(DOCUMENT_ID), 3
        );

        assertThat(result.question()).isEqualTo("question");
        assertThat(result.results())
                .extracting(item -> item.metadata().get("chunk_id"))
                .containsExactly("A", "C", "B");
        assertThat(result.results().getFirst().metadata())
                .containsEntry("retrieval_mode", "hybrid")
                .containsEntry("vector_rank", 1)
                .containsEntry("keyword_rank", 2)
                .containsKeys("rrf_score", "vector_score", "keyword_score");
    }

    @Test
    void removesHighlyOverlappingAdjacentChunksAndUsesTheNextCandidate() {
        String shared = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu";
        String overlapping = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda extra";
        List<SimilaritySearchResult> vectors = List.of(
                vector("A", 1, 0.95, shared),
                vector("A2", 2, 0.94, overlapping),
                vector("B", 8, 0.80, "completely different useful evidence about another concept")
        );
        when(vectorRetrieval.searchAcrossDocuments("question", List.of(DOCUMENT_ID), 20))
                .thenReturn(response(vectors));
        when(keywordRetrieval.search("question", List.of(DOCUMENT_ID), 20)).thenReturn(List.of(
                keyword("A", 10, shared, 1),
                keyword("A2", 9, overlapping, 2),
                keyword("B", 8, "completely different useful evidence about another concept", 8)
        ));

        MultiDocumentSimilaritySearchResponse result = service.searchAcrossDocuments(
                "question", List.of(DOCUMENT_ID), 2
        );

        assertThat(result.results())
                .extracting(item -> item.metadata().get("chunk_id"))
                .containsExactly("A", "B");
    }

    @Test
    void fallsBackToVectorRankingWhenElasticsearchIsUnavailable() {
        List<SimilaritySearchResult> vectors = List.of(
                vector("A", 1, 0.91, "A"),
                vector("B", 2, 0.84, "B")
        );
        when(vectorRetrieval.searchAcrossDocuments("question", List.of(DOCUMENT_ID), 20))
                .thenReturn(response(vectors));
        when(keywordRetrieval.search("question", List.of(DOCUMENT_ID), 20)).thenReturn(List.of());

        MultiDocumentSimilaritySearchResponse result = service.searchAcrossDocuments(
                "question", List.of(DOCUMENT_ID), 1
        );

        assertThat(result.results()).singleElement().satisfies(item -> {
            assertThat(item.metadata()).containsEntry("retrieval_mode", "vector");
            assertThat(item.score()).isEqualTo(0.91);
        });
    }

    private SimilaritySearchResult vector(
            String chunkId, int chunkIndex, double score, String text) {
        return new SimilaritySearchResult(
                chunkIndex,
                score,
                text,
                Map.of(
                        "chunk_id", chunkId,
                        "document_id", DOCUMENT_ID,
                        "chunk_index", chunkIndex,
                        "section_title", "Section"
                )
        );
    }

    private KeywordSearchResult keyword(
            String chunkId, double score, String text, int chunkIndex) {
        return new KeywordSearchResult(
                chunkId,
                score,
                text,
                Map.of(
                        "chunk_id", chunkId,
                        "document_id", DOCUMENT_ID,
                        "chunk_index", chunkIndex,
                        "section_title", "Section"
                )
        );
    }

    private MultiDocumentSimilaritySearchResponse response(
            List<SimilaritySearchResult> results) {
        return new MultiDocumentSimilaritySearchResponse(
                "question", List.of(DOCUMENT_ID), 20, results.size(), results
        );
    }
}
