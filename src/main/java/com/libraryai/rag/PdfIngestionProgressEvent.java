package com.libraryai.rag;

/** One real ingestion milestone sent to the UI. */
public record PdfIngestionProgressEvent(
        PdfIngestionStage stage,
        String message,
        int completedChunks,
        int totalChunks,
        int geminiEmbeddingTokens) {
}
