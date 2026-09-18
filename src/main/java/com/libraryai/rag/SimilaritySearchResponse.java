package com.libraryai.rag;

import java.util.List;

/** Inspectable retrieval response before final RAG answer generation is added. */
public record SimilaritySearchResponse(
        String question,
        String documentId,
        int topK,
        int resultCount,
        List<SimilaritySearchResult> results) {
}
