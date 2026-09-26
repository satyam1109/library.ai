package com.libraryai.rag;

import java.util.Map;

/** One Elasticsearch BM25 candidate before it is fused with vector results. */
public record KeywordSearchResult(
        String chunkId,
        double score,
        String text,
        Map<String, Object> metadata) {
}
