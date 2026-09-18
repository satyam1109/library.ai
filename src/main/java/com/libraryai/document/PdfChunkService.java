package com.libraryai.document;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * Extracts page-level text from a PDF and converts it into smaller Spring AI
 * Documents that can later be sent to an embedding model.
 */
@Service
public class PdfChunkService {

    private static final Logger log = LoggerFactory.getLogger(PdfChunkService.class);

    private final PdfTextCleaningService textCleaningService;
    private final StructureAwareChunkingService chunkingService;

    public PdfChunkService(
            PdfTextCleaningService textCleaningService,
            StructureAwareChunkingService chunkingService) {
        this.textCleaningService = textCleaningService;
        this.chunkingService = chunkingService;
    }

    public PdfChunkResult chunkPdf(Resource pdfResource) {
        // PagePdfDocumentReader uses PDFBox and normally produces one Document per page.
        PagePdfDocumentReader reader = new PagePdfDocumentReader(pdfResource);
        List<Document> pageDocuments = reader.read();

        log.info("PDF extraction produced {} page documents", pageDocuments.size());

        // Cleaning happens before splitting so layout noise does not influence
        // chunk boundaries or later embedding vectors.
        List<Document> cleanedDocuments = textCleaningService.clean(pageDocuments);

        boolean containsText = cleanedDocuments.stream()
                .map(Document::getText)
                .anyMatch(text -> text != null && !text.isBlank());
        if (!containsText) {
            throw new IllegalArgumentException("The PDF contains no extractable text");
        }

        // Structure-aware packing keeps fitting code blocks whole and uses
        // token-based fallback splitting only for oversized blocks.
        List<Document> splitDocuments = chunkingService.chunk(cleanedDocuments);
        List<Document> indexedChunks = addChunkIndexes(splitDocuments);

        log.info("PDF chunking produced {} chunks", indexedChunks.size());

        return new PdfChunkResult(pageDocuments.size(), List.copyOf(indexedChunks));
    }

    private List<Document> addChunkIndexes(List<Document> chunks) {
        List<Document> indexedChunks = new ArrayList<>(chunks.size());

        for (int index = 0; index < chunks.size(); index++) {
            Document indexedChunk = chunks.get(index)
                    .mutate()
                    .metadata(LibraryDocumentMetadata.CHUNK_INDEX, index)
                    .build();
            indexedChunks.add(indexedChunk);
        }

        return indexedChunks;
    }
}
