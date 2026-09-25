package com.libraryai.rag;

/** Safe ingestion failure returned over SSE. */
public record PdfIngestionFailureEvent(String message) {
}
