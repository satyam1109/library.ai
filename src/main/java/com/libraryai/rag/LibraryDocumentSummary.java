package com.libraryai.rag;

import java.time.OffsetDateTime;

/** One parent document tracked independently from its vectorized chunks. */
public record LibraryDocumentSummary(
        String documentId,
        String fileName,
        String contentFingerprint,
        String status,
        int chunkCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
