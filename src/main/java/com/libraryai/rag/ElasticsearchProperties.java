package com.libraryai.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings for the secondary lexical index and application-level rank fusion. */
@ConfigurationProperties("library.ai.elasticsearch")
public record ElasticsearchProperties(
        boolean enabled,
        String baseUrl,
        String indexName,
        int candidateCount,
        int rrfRankConstant,
        double redundancyOverlapThreshold) {

    public ElasticsearchProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Elasticsearch base URL must be configured");
        }
        if (indexName == null || !indexName.matches("[a-z0-9][a-z0-9_-]*")) {
            throw new IllegalArgumentException("Elasticsearch index name is invalid");
        }
        if (candidateCount < 1) {
            throw new IllegalArgumentException("Elasticsearch candidate count must be positive");
        }
        if (rrfRankConstant < 1) {
            throw new IllegalArgumentException("RRF rank constant must be positive");
        }
        if (redundancyOverlapThreshold <= 0 || redundancyOverlapThreshold > 1) {
            throw new IllegalArgumentException("Redundancy overlap threshold must be in (0, 1]");
        }
    }
}
