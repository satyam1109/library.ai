package com.libraryai.rag;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** HTTP entry point for chunking, embedding, and storing one PDF. */
@RestController
@RequestMapping("/api/documents")
public class PdfIngestionController {

    private final PdfIngestionService ingestionService;
    private final DocumentCatalogRepository documentCatalog;

    public PdfIngestionController(
            PdfIngestionService ingestionService,
            DocumentCatalogRepository documentCatalog) {
        this.ingestionService = ingestionService;
        this.documentCatalog = documentCatalog;
    }

    /** Lists parent documents without returning their potentially large chunks. */
    @GetMapping
    public List<LibraryDocumentSummary> documents() {
        return this.documentCatalog.findAll();
    }

    @PostMapping(value = "/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PdfIngestionResponse ingest(@RequestPart("file") MultipartFile file) {
        validatePdf(file);
        try {
            return this.ingestionService.ingest(file.getOriginalFilename(), file.getBytes());
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded PDF", exception);
        }
    }

    private void validatePdf(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PDF file must not be empty");
        }
        if (fileName == null || !fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PDF files are supported");
        }
    }
}
