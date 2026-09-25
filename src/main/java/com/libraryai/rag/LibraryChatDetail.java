package com.libraryai.rag;

import java.time.OffsetDateTime;
import java.util.List;

/** Complete chat state returned when the user opens a chat. */
public record LibraryChatDetail(
        String chatId,
        String title,
        int contextVersion,
        List<LibraryDocumentSummary> documents,
        List<LibraryChatMessage> messages,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
