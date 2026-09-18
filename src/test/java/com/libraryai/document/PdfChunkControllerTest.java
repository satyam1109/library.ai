package com.libraryai.document;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfChunkControllerTest {

    private final StubPdfChunkService chunkService = new StubPdfChunkService();
    private final PdfChunkController controller = new PdfChunkController(chunkService);
    private final MockMultipartFile pdf = new MockMultipartFile(
            "file",
            "book.pdf",
            "application/pdf",
            "pdf-content".getBytes()
    );

    @Test
    void returnsEveryGeneratedChunkWhenPreviewLimitIsOmitted() {
        chunkService.result = resultWithThreeChunks();

        PdfChunkResponse response = controller.previewChunks(pdf, null);

        assertThat(response.totalChunkCount()).isEqualTo(3);
        assertThat(response.previewCount()).isEqualTo(3);
        assertThat(response.chunks()).hasSize(3);
    }

    @Test
    void appliesAnExplicitPositivePreviewLimit() {
        chunkService.result = resultWithThreeChunks();

        PdfChunkResponse response = controller.previewChunks(pdf, 2);

        assertThat(response.totalChunkCount()).isEqualTo(3);
        assertThat(response.previewCount()).isEqualTo(2);
        assertThat(response.chunks()).hasSize(2);
    }

    @Test
    void rejectsANonPositivePreviewLimit() {
        assertThatThrownBy(() -> controller.previewChunks(pdf, 0))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getReason()).isEqualTo("previewLimit must be at least 1");
                });
    }

    @Test
    void exposesChunkDiagnosticsAsTopLevelPreviewFields() {
        Document diagnosticChunk = new Document("chunk text", Map.ofEntries(
                Map.entry(LibraryDocumentMetadata.CHUNK_INDEX, 7),
                Map.entry(LibraryDocumentMetadata.ESTIMATED_TOKEN_COUNT, 42),
                Map.entry(LibraryDocumentMetadata.CONTENT_TOKEN_COUNT, 30),
                Map.entry(LibraryDocumentMetadata.CHUNK_CHARACTER_COUNT, 10),
                Map.entry(LibraryDocumentMetadata.SECTION_TITLE, "2. Retrieval"),
                Map.entry(LibraryDocumentMetadata.SUBSECTION_TITLES, List.of("2.1 Similarity")),
                Map.entry(LibraryDocumentMetadata.HEADING_PATH, List.of("2. Retrieval", "2.1 Similarity")),
                Map.entry(LibraryDocumentMetadata.MAX_HEADING_LEVEL, 2),
                Map.entry(LibraryDocumentMetadata.STRUCTURAL_BOUNDARY, "same_topic_token_limit"),
                Map.entry(LibraryDocumentMetadata.CONTAINS_CODE, true),
                Map.entry(LibraryDocumentMetadata.CONTAINS_LIST, false),
                Map.entry(LibraryDocumentMetadata.OVERLAP_APPLIED, true),
                Map.entry(LibraryDocumentMetadata.OVERLAP_TOKEN_COUNT, 12),
                Map.entry(LibraryDocumentMetadata.PAGE_NUMBERS, List.of(2, 3)),
                Map.entry(LibraryDocumentMetadata.SPANS_PAGES, true),
                Map.entry(LibraryDocumentMetadata.CONTINUES_FROM_PREVIOUS_PAGE, true)
        ));
        chunkService.result = new PdfChunkResult(2, List.of(diagnosticChunk));

        PdfChunkPreview preview = controller.previewChunks(pdf, null).chunks().getFirst();

        assertThat(preview.chunkIndex()).isEqualTo(7);
        assertThat(preview.tokenCount()).isEqualTo(42);
        assertThat(preview.contentTokenCount()).isEqualTo(30);
        assertThat(preview.characterCount()).isEqualTo(10);
        assertThat(preview.sectionTitle()).isEqualTo("2. Retrieval");
        assertThat(preview.subsectionTitles()).containsExactly("2.1 Similarity");
        assertThat(preview.headingPath()).containsExactly("2. Retrieval", "2.1 Similarity");
        assertThat(preview.maxHeadingLevel()).isEqualTo(2);
        assertThat(preview.structuralBoundary()).isEqualTo("same_topic_token_limit");
        assertThat(preview.containsCode()).isTrue();
        assertThat(preview.overlapApplied()).isTrue();
        assertThat(preview.overlapTokenCount()).isEqualTo(12);
        assertThat(preview.pageNumbers()).containsExactly(2, 3);
        assertThat(preview.spansPages()).isTrue();
        assertThat(preview.continuesFromPreviousPage()).isTrue();
    }

    private PdfChunkResult resultWithThreeChunks() {
        List<Document> chunks = List.of(
                chunk(0, "first"),
                chunk(1, "second"),
                chunk(2, "third")
        );
        return new PdfChunkResult(1, chunks);
    }

    private Document chunk(int index, String text) {
        return new Document(text, Map.of(LibraryDocumentMetadata.CHUNK_INDEX, index));
    }

    private static final class StubPdfChunkService extends PdfChunkService {

        private PdfChunkResult result;

        private StubPdfChunkService() {
            super(new PdfTextCleaningService(), new StructureAwareChunkingService());
        }

        @Override
        public PdfChunkResult chunkPdf(Resource pdfResource) {
            return result;
        }
    }
}
