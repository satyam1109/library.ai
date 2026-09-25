package com.libraryai.rag;

import java.util.List;

/** Current rolling summary and the messages that have not yet been summarized. */
public record ConversationMemoryState(
        String summary,
        long summarizedThroughOrder,
        List<StoredChatMessage> unsummarizedMessages) {
}
