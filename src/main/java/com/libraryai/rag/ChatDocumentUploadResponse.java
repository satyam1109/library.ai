package com.libraryai.rag;

/** Result of ingesting a PDF and attaching it to a chat in one request. */
public record ChatDocumentUploadResponse(
        PdfIngestionResponse ingestion,
        LibraryChatDetail chat) {
}
