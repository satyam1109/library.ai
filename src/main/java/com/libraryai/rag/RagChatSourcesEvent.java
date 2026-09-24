package com.libraryai.rag;

import java.util.List;

/** Retrieved evidence sent before Gemini starts streaming the answer. */
public record RagChatSourcesEvent(
        int retrievedChunkCount,
        List<SimilaritySearchResult> sources) {
}
