package com.libraryai.rag;

import java.time.OffsetDateTime;

/** Lightweight chat information used by the sidebar. */
public record LibraryChatSummary(
        String chatId,
        String title,
        int contextVersion,
        int documentCount,
        int messageCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
