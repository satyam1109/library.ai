package com.libraryai.rag;

import java.util.List;

/** Globally ranked vector results restricted to the selected documents. */
public record MultiDocumentSimilaritySearchResponse(
        String question,
        List<String> documentIds,
        int topK,
        int resultCount,
        List<SimilaritySearchResult> results) {
}
