package com.libraryai.document;

import java.util.List;

import org.springframework.ai.document.Document;

/**
 * Internal result of the extraction and chunking pipeline.
 */
public record PdfChunkResult(
        int sourceDocumentCount,
        List<Document> chunks
) {
}
