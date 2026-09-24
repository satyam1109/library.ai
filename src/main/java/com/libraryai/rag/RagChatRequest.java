package com.libraryai.rag;

import java.util.List;

/** One conversational RAG turn scoped to one, two, or three documents. */
public record RagChatRequest(
        String conversationId,
        List<String> documentIds,
        String message,
        Integer topK) {
}
