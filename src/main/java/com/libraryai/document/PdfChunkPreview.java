package com.libraryai.document;

import java.util.List;
import java.util.Map;

/**
 * A JSON-friendly view of one Spring AI Document chunk.
 */
public record PdfChunkPreview(
        int chunkIndex,
        String text,
        int tokenCount,
        int contentTokenCount,
        int characterCount,
        String sectionTitle,
        List<String> subsectionTitles,
        List<String> headingPath,
        int maxHeadingLevel,
        String structuralBoundary,
        boolean containsCode,
        boolean containsList,
        boolean overlapApplied,
        int overlapTokenCount,
        List<Integer> pageNumbers,
        boolean spansPages,
        boolean continuesFromPreviousPage,
        Map<String, Object> metadata
) {
}
