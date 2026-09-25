package com.libraryai.rag;

import java.util.List;

/** Bounded conversation context prepared for one RAG prompt. */
public record PreparedConversationMemory(
        String summary,
        List<StoredChatMessage> recentMessages,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens) {

    public boolean hasSummary() {
        return summary != null && !summary.isBlank();
    }
}
