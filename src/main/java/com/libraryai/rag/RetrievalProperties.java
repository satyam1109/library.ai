package com.libraryai.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application-owned retrieval settings that must remain consistent between
 * Gemini embedding generation and the pgvector table.
 */
@ConfigurationProperties("library.ai.retrieval")
public record RetrievalProperties(
        String embeddingModel,
        int embeddingDimensions,
        int defaultTopK,
        int maxTopK,
        String schemaName,
        String tableName) {

    public RetrievalProperties {
        if (embeddingModel == null || embeddingModel.isBlank()) {
            throw new IllegalArgumentException("Embedding model must be configured");
        }
        if (embeddingDimensions < 1) {
            throw new IllegalArgumentException("Embedding dimensions must be positive");
        }
        if (defaultTopK < 1 || maxTopK < defaultTopK) {
            throw new IllegalArgumentException("Invalid top-K configuration");
        }
        validateIdentifier(schemaName, "schema-name");
        validateIdentifier(tableName, "table-name");
    }

    private static void validateIdentifier(String value, String propertyName) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(propertyName + " must be a safe SQL identifier");
        }
    }
}
