package com.libraryai.rag;

import java.time.OffsetDateTime;
import java.util.List;

/** A persisted chat message, including evidence for assistant messages. */
public record LibraryChatMessage(
        String messageId,
        String role,
        String text,
        int contextVersion,
        List<SimilaritySearchResult> sources,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        OffsetDateTime createdAt) {
}
