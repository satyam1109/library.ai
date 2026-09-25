package com.libraryai.rag;

import java.util.List;

/** Answer, evidence, and usage for one multi-document RAG chat turn. */
public record RagChatResponse(
        String chatId,
        int contextVersion,
        List<String> documentIds,
        String message,
        String answer,
        int retrievedChunkCount,
        List<SimilaritySearchResult> sources,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens) {
}
