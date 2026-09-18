package com.libraryai.document;

import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Temporary learning endpoint for inspecting chunks before embeddings and
 * persistence are introduced.
 */
@RestController
@RequestMapping("/api/documents")
public class PdfChunkController {

    private final PdfChunkService pdfChunkService;

    public PdfChunkController(PdfChunkService pdfChunkService) {
        this.pdfChunkService = pdfChunkService;
    }

    @PostMapping(value = "/chunks/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PdfChunkResponse previewChunks(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) Integer previewLimit) {

        validateUpload(file, previewLimit);

        try {
            PdfChunkResult result = pdfChunkService.chunkPdf(file.getResource());
            // No limit means "show every generated chunk". An explicitly supplied
            // limit is still useful when inspecting a large PDF response.
            int numberOfPreviews = previewLimit == null
                    ? result.chunks().size()
                    : Math.min(previewLimit, result.chunks().size());

            List<PdfChunkPreview> previews = result.chunks()
                    .subList(0, numberOfPreviews)
                    .stream()
                    .map(this::toPreview)
                    .toList();

            return new PdfChunkResponse(
                    file.getOriginalFilename(),
                    result.sourceDocumentCount(),
                    result.chunks().size(),
                    previews.size(),
                    previews
            );
        } catch (RuntimeException exception) {
            // PDFBox reports malformed, encrypted, or unreadable PDFs here.
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "The uploaded file could not be read as a text PDF",
                    exception
            );
        }
    }

    private PdfChunkPreview toPreview(Document chunk) {
        Map<String, Object> metadata = chunk.getMetadata();

        return new PdfChunkPreview(
                intMetadata(metadata, LibraryDocumentMetadata.CHUNK_INDEX, -1),
                chunk.getText(),
                intMetadata(metadata, LibraryDocumentMetadata.ESTIMATED_TOKEN_COUNT, 0),
                intMetadata(metadata, LibraryDocumentMetadata.CONTENT_TOKEN_COUNT, 0),
                intMetadata(metadata, LibraryDocumentMetadata.CHUNK_CHARACTER_COUNT, chunk.getText().length()),
                stringMetadata(metadata, LibraryDocumentMetadata.SECTION_TITLE),
                stringListMetadata(metadata, LibraryDocumentMetadata.SUBSECTION_TITLES),
                stringListMetadata(metadata, LibraryDocumentMetadata.HEADING_PATH),
                intMetadata(metadata, LibraryDocumentMetadata.MAX_HEADING_LEVEL, 0),
                stringMetadata(metadata, LibraryDocumentMetadata.STRUCTURAL_BOUNDARY),
                booleanMetadata(metadata, LibraryDocumentMetadata.CONTAINS_CODE),
                booleanMetadata(metadata, LibraryDocumentMetadata.CONTAINS_LIST),
                booleanMetadata(metadata, LibraryDocumentMetadata.OVERLAP_APPLIED),
                intMetadata(metadata, LibraryDocumentMetadata.OVERLAP_TOKEN_COUNT, 0),
                integerListMetadata(metadata, LibraryDocumentMetadata.PAGE_NUMBERS),
                booleanMetadata(metadata, LibraryDocumentMetadata.SPANS_PAGES),
                booleanMetadata(metadata, LibraryDocumentMetadata.CONTINUES_FROM_PREVIOUS_PAGE),
                metadata
        );
    }

    private int intMetadata(Map<String, Object> metadata, String key, int fallback) {
        Object value = metadata.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private boolean booleanMetadata(Map<String, Object> metadata, String key) {
        return Boolean.TRUE.equals(metadata.get(key));
    }

    private String stringMetadata(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? "" : value.toString();
    }

    private List<Integer> integerListMetadata(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::intValue)
                .toList();
    }

    private List<String> stringListMetadata(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }

    private void validateUpload(MultipartFile file, Integer previewLimit) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PDF file must not be empty");
        }

        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.toLowerCase().endsWith(".pdf")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PDF files are supported");
        }

        if (previewLimit != null && previewLimit < 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "previewLimit must be at least 1"
            );
        }
    }
}
