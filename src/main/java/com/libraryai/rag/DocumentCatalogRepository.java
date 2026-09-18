package com.libraryai.rag;

import java.util.List;
import java.util.UUID;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Owns the parent-document catalog. Chunk vectors remain in Spring AI's
 * pgvector table and refer to this parent through document_id metadata.
 */
@Repository
public class DocumentCatalogRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RetrievalProperties properties;

    public DocumentCatalogRepository(JdbcTemplate jdbcTemplate, RetrievalProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    @PostConstruct
    void initializeSchema() {
        this.jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS %s.library_documents (
                    document_id UUID PRIMARY KEY,
                    content_fingerprint VARCHAR(64) NOT NULL UNIQUE,
                    original_file_name TEXT NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    chunk_count INTEGER NOT NULL DEFAULT 0,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """.formatted(this.properties.schemaName()));
    }

    public UUID register(String fileName, String contentFingerprint) {
        String sql = """
                INSERT INTO %s.library_documents (
                    document_id, content_fingerprint, original_file_name, status, chunk_count
                ) VALUES (?, ?, ?, 'PROCESSING', 0)
                ON CONFLICT (content_fingerprint) DO UPDATE SET
                    original_file_name = EXCLUDED.original_file_name,
                    status = 'PROCESSING',
                    updated_at = CURRENT_TIMESTAMP
                RETURNING document_id
                """.formatted(this.properties.schemaName());
        return this.jdbcTemplate.queryForObject(
                sql, UUID.class, UUID.randomUUID(), contentFingerprint, fileName
        );
    }

    /** Speeds up document-scoped JSON metadata filters in the vector table. */
    public void ensureChunkOwnershipIndex() {
        this.jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS library_chunks_document_id_idx
                ON %s.%s ((metadata->>'document_id'))
                """.formatted(this.properties.schemaName(), this.properties.tableName()));
    }

    public void markReady(UUID documentId, int chunkCount) {
        this.jdbcTemplate.update("""
                UPDATE %s.library_documents
                SET status = 'READY', chunk_count = ?, updated_at = CURRENT_TIMESTAMP
                WHERE document_id = ?
                """.formatted(this.properties.schemaName()), chunkCount, documentId);
    }

    public void markFailed(UUID documentId) {
        this.jdbcTemplate.update("""
                UPDATE %s.library_documents
                SET status = CASE WHEN chunk_count > 0 THEN 'READY' ELSE 'FAILED' END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE document_id = ?
                """.formatted(this.properties.schemaName()), documentId);
    }

    public boolean isReady(UUID documentId) {
        Boolean ready = this.jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM %s.library_documents
                    WHERE document_id = ? AND status = 'READY' AND chunk_count > 0
                )
                """.formatted(this.properties.schemaName()), Boolean.class, documentId);
        return Boolean.TRUE.equals(ready);
    }

    public List<LibraryDocumentSummary> findAll() {
        return this.jdbcTemplate.query("""
                SELECT document_id, original_file_name, content_fingerprint,
                       status, chunk_count, created_at, updated_at
                FROM %s.library_documents
                ORDER BY created_at DESC
                """.formatted(this.properties.schemaName()), (resultSet, rowNumber) ->
                new LibraryDocumentSummary(
                        resultSet.getObject("document_id", UUID.class).toString(),
                        resultSet.getString("original_file_name"),
                        resultSet.getString("content_fingerprint"),
                        resultSet.getString("status"),
                        resultSet.getInt("chunk_count"),
                        resultSet.getObject("created_at", java.time.OffsetDateTime.class),
                        resultSet.getObject("updated_at", java.time.OffsetDateTime.class)
                ));
    }
}
