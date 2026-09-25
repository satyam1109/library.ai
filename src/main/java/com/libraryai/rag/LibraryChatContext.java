package com.libraryai.rag;

import java.util.List;

/** Server-owned retrieval boundary for one chat. */
public record LibraryChatContext(
        String chatId,
        String title,
        int contextVersion,
        List<String> documentIds) {
}
