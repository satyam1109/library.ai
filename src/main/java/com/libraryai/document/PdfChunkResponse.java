package com.libraryai.document;

import java.util.List;

/**
 * Summarizes chunking and returns every chunk unless a preview limit was given.
 */
public record PdfChunkResponse(
        String fileName,
        int sourceDocumentCount,
        int totalChunkCount,
        int previewCount,
        List<PdfChunkPreview> chunks
) {
}
