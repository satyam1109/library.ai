package com.libraryai.rag;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.libraryai.document.LibraryDocumentMetadata;
import com.libraryai.document.PdfChunkResult;
import com.libraryai.document.PdfChunkService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PdfIngestionServiceTest {

    private final PdfChunkService chunkService = mock(PdfChunkService.class);
    private final VectorStore vectorStore = mock(VectorStore.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final DocumentCatalogRepository documentCatalog = mock(DocumentCatalogRepository.class);
    private final UUID documentId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private final PdfIngestionService service = new PdfIngestionService(
            chunkService,
            vectorStore,
            jdbcTemplate,
            new RetrievalProperties("gemini-embedding-001", 768, 5, 20, "public", "library_chunks"),
            documentCatalog
    );

    @Test
    void skipsEmbeddingWhenTheExactIngestionAlreadyExists() {
        when(chunkService.chunkPdf(any(Resource.class))).thenReturn(chunks());
        when(documentCatalog.register(anyString(), anyString())).thenReturn(documentId);
        when(jdbcTemplate.queryForObject(
                anyString(), eq(Integer.class), anyString(), anyString()
        )).thenReturn(2);

        PdfIngestionResponse response = service.ingest("book.pdf", "same-pdf".getBytes());

        assertThat(response.skippedAsDuplicate()).isTrue();
        assertThat(response.documentId()).isEqualTo(documentId.toString());
        assertThat(response.storedChunkCount()).isEqualTo(2);
        assertThat(response.geminiEmbeddingTokens()).isZero();
        verify(vectorStore, never()).add(any());
        verify(documentCatalog).markReady(documentId, 2);
    }

    @Test
    void addsStableIdentityAndEmbeddingMetadataBeforeVectorStorage() {
        when(chunkService.chunkPdf(any(Resource.class))).thenReturn(chunks());
        when(documentCatalog.register(anyString(), anyString())).thenReturn(documentId);
        when(jdbcTemplate.queryForObject(
                anyString(), eq(Integer.class), anyString(), anyString()
        ))
                .thenReturn(0, 2);

        PdfIngestionResponse response = service.ingest("book.pdf", "new-pdf".getBytes());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> documents = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(documents.capture());
        verify(jdbcTemplate).update(anyString(), anyString(), anyString());

        assertThat(response.skippedAsDuplicate()).isFalse();
        // The mocked vector store does not call the embedding adapter.
        assertThat(response.geminiEmbeddingTokens()).isZero();
        assertThat(documents.getValue()).hasSize(2).allSatisfy(document -> {
            assertThat(document.getId()).matches("[a-f0-9-]{36}");
            assertThat(document.getMetadata())
                    .containsEntry(LibraryDocumentMetadata.EMBEDDING_MODEL, "gemini-embedding-001")
                    .containsEntry(LibraryDocumentMetadata.EMBEDDING_DIMENSIONS, 768)
                    .containsEntry(LibraryDocumentMetadata.DOCUMENT_ID, documentId.toString())
                    .containsKeys(
                            LibraryDocumentMetadata.DOCUMENT_FINGERPRINT,
                            LibraryDocumentMetadata.INGESTION_FINGERPRINT,
                            LibraryDocumentMetadata.CHUNK_FINGERPRINT
                    );
        });
        verify(documentCatalog).markReady(documentId, 2);
    }

    private PdfChunkResult chunks() {
        return new PdfChunkResult(1, List.of(
                new Document("first chunk", Map.of(LibraryDocumentMetadata.CHUNK_INDEX, 0)),
                new Document("second chunk", Map.of(LibraryDocumentMetadata.CHUNK_INDEX, 1))
        ));
    }
}
