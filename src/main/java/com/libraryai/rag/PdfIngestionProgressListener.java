package com.libraryai.rag;

/** Receives progress without coupling the ingestion service to HTTP/SSE. */
@FunctionalInterface
public interface PdfIngestionProgressListener {

    PdfIngestionProgressListener NONE = event -> { };

    void onProgress(PdfIngestionProgressEvent event);
}
