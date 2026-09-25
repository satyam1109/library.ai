package com.libraryai.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.libraryai.document.LibraryDocumentMetadata;
import com.libraryai.document.PdfChunkResult;
import com.libraryai.document.PdfChunkService;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Reuses the existing extraction/chunking pipeline and persists the resulting
 * Spring AI Documents in pgvector with deterministic identities.
 */
@Service
public class PdfIngestionService {

    private final PdfChunkService pdfChunkService;
    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final RetrievalProperties properties;
    private final DocumentCatalogRepository documentCatalog;

    public PdfIngestionService(
            PdfChunkService pdfChunkService,
            VectorStore vectorStore,
            JdbcTemplate jdbcTemplate,
            RetrievalProperties properties,
            DocumentCatalogRepository documentCatalog) {
        this.pdfChunkService = pdfChunkService;
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.documentCatalog = documentCatalog;
    }

    public synchronized PdfIngestionResponse ingest(String fileName, byte[] pdfBytes) {
        return ingest(fileName, pdfBytes, PdfIngestionProgressListener.NONE);
    }

    public synchronized PdfIngestionResponse ingest(
            String fileName,
            byte[] pdfBytes,
            PdfIngestionProgressListener progressListener) {
        String documentFingerprint = sha256(pdfBytes);
        UUID documentId = this.documentCatalog.register(fileName, documentFingerprint);
        this.documentCatalog.ensureChunkOwnershipIndex();

        try {
            progressListener.onProgress(new PdfIngestionProgressEvent(
                    PdfIngestionStage.PROCESSING_PDF,
                    "Extracting, cleaning, and chunking the PDF",
                    0,
                    0,
                    0
            ));
            PdfChunkResult chunkResult = this.pdfChunkService.chunkPdf(namedResource(fileName, pdfBytes));
            String ingestionFingerprint = ingestionFingerprint(documentFingerprint, chunkResult.chunks());
            int expectedChunks = chunkResult.chunks().size();

            int existingChunks = countByIngestionFingerprint(documentId, ingestionFingerprint);
            if (existingChunks == expectedChunks) {
                this.documentCatalog.markReady(documentId, existingChunks);
                return response(
                        documentId, fileName, documentFingerprint, chunkResult,
                        existingChunks, 0, true
                );
            }

            List<Document> indexedDocuments = addStableIdentity(
                    chunkResult.chunks(), documentId, documentFingerprint, ingestionFingerprint
            );

            progressListener.onProgress(new PdfIngestionProgressEvent(
                    PdfIngestionStage.INDEXING,
                    "Creating Gemini embeddings and indexing chunks",
                    0,
                    indexedDocuments.size(),
                    0
            ));
            // PgVectorStore embeds with RETRIEVAL_DOCUMENT and upserts the original
            // text plus the complete metadata JSON alongside every vector. The
            // adapter reports each Gemini batch and its exact usage metadata.
            int embeddingTokens = DocumentEmbeddingProgressContext.track(
                    indexedDocuments.size(),
                    progressListener,
                    () -> this.vectorStore.add(indexedDocuments)
            );

            progressListener.onProgress(new PdfIngestionProgressEvent(
                    PdfIngestionStage.FINALIZING,
                    "Finalizing the searchable document",
                    indexedDocuments.size(),
                    indexedDocuments.size(),
                    embeddingTokens
            ));

            // Replace older chunking results for this document only after the new
            // vectors have been embedded and stored successfully.
            deleteOlderIngestions(documentId, ingestionFingerprint);
            int storedChunks = countByIngestionFingerprint(documentId, ingestionFingerprint);
            this.documentCatalog.markReady(documentId, storedChunks);
            return response(
                    documentId, fileName, documentFingerprint, chunkResult,
                    storedChunks, embeddingTokens, false
            );
        } catch (RuntimeException exception) {
            this.documentCatalog.markFailed(documentId);
            throw exception;
        }
    }

    private List<Document> addStableIdentity(
            List<Document> chunks,
            UUID documentId,
            String documentFingerprint,
            String ingestionFingerprint) {
        List<Document> documents = new ArrayList<>(chunks.size());

        for (int index = 0; index < chunks.size(); index++) {
            Document chunk = chunks.get(index);
            String chunkFingerprint = sha256(chunk.getText().getBytes(StandardCharsets.UTF_8));
            String idSource = documentFingerprint + ":" + index + ":" + chunkFingerprint;
            String stableId = UUID.nameUUIDFromBytes(idSource.getBytes(StandardCharsets.UTF_8)).toString();

            Map<String, Object> metadata = new java.util.HashMap<>(chunk.getMetadata());
            metadata.put(LibraryDocumentMetadata.DOCUMENT_ID, documentId.toString());
            metadata.put(LibraryDocumentMetadata.DOCUMENT_FINGERPRINT, documentFingerprint);
            metadata.put(LibraryDocumentMetadata.INGESTION_FINGERPRINT, ingestionFingerprint);
            metadata.put(LibraryDocumentMetadata.CHUNK_FINGERPRINT, chunkFingerprint);
            metadata.put(LibraryDocumentMetadata.EMBEDDING_MODEL, this.properties.embeddingModel());
            metadata.put(LibraryDocumentMetadata.EMBEDDING_DIMENSIONS, this.properties.embeddingDimensions());

            documents.add(new Document(stableId, chunk.getText(), metadata));
        }
        return List.copyOf(documents);
    }

    private String ingestionFingerprint(String documentFingerprint, List<Document> chunks) {
        StringBuilder identity = new StringBuilder(documentFingerprint);
        for (Document chunk : chunks) {
            identity.append('\u001f').append(chunk.getText());
        }
        return sha256(identity.toString().getBytes(StandardCharsets.UTF_8));
    }

    private int countByIngestionFingerprint(UUID documentId, String fingerprint) {
        String sql = "SELECT COUNT(*) FROM " + qualifiedTable()
                + " WHERE metadata->>'document_id' = ?"
                + " AND metadata->>'ingestion_fingerprint' = ?";
        Integer count = this.jdbcTemplate.queryForObject(
                sql, Integer.class, documentId.toString(), fingerprint
        );
        return count == null ? 0 : count;
    }

    private void deleteOlderIngestions(UUID documentId, String currentIngestionFingerprint) {
        String sql = "DELETE FROM " + qualifiedTable()
                + " WHERE metadata->>'document_id' = ?"
                + " AND metadata->>'ingestion_fingerprint' <> ?";
        this.jdbcTemplate.update(sql, documentId.toString(), currentIngestionFingerprint);
    }

    private String qualifiedTable() {
        return this.properties.schemaName() + "." + this.properties.tableName();
    }

    private ByteArrayResource namedResource(String fileName, byte[] contents) {
        return new ByteArrayResource(contents) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
    }

    private PdfIngestionResponse response(
            UUID documentId,
            String fileName,
            String documentFingerprint,
            PdfChunkResult result,
            int storedChunks,
            int embeddingTokens,
            boolean skipped) {
        return new PdfIngestionResponse(
                documentId.toString(),
                fileName,
                documentFingerprint,
                result.sourceDocumentCount(),
                result.chunks().size(),
                storedChunks,
                embeddingTokens,
                skipped
        );
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
