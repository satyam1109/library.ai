package com.libraryai.rag;

import java.util.List;

/** Gemini answer plus the retrieved evidence and token usage for inspection. */
public record RagAnswerResponse(
        String documentId,
        String question,
        String answer,
        int retrievedChunkCount,
        List<SimilaritySearchResult> sources,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens) {
}
