package com.libraryai.rag;

/** A question scoped to one indexed document, plus an optional result limit. */
public record SimilaritySearchRequest(
        String question,
        Integer topK,
        String documentId) {
}
