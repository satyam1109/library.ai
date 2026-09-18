package com.libraryai.rag;

/** Result of chunking and indexing one uploaded PDF. */
public record PdfIngestionResponse(
        String documentId,
        String fileName,
        String documentFingerprint,
        int sourceDocumentCount,
        int generatedChunkCount,
        int storedChunkCount,
        boolean skippedAsDuplicate) {
}
