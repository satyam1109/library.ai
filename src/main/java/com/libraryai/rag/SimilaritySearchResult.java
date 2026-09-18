package com.libraryai.rag;

import java.util.Map;

/** One retrieved chunk. The vector itself intentionally stays internal. */
public record SimilaritySearchResult(
        int rank,
        double score,
        String text,
        Map<String, Object> metadata) {
}
