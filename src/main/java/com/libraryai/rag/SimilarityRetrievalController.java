package com.libraryai.rag;

import java.util.Collections;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Temporary inspection API that stops after similarity retrieval. */
@RestController
@RequestMapping("/api/retrieval")
public class SimilarityRetrievalController {

    private final SimilarityRetrievalService retrievalService;
    private final HybridRetrievalService hybridRetrievalService;

    public SimilarityRetrievalController(
            SimilarityRetrievalService retrievalService,
            HybridRetrievalService hybridRetrievalService) {
        this.retrievalService = retrievalService;
        this.hybridRetrievalService = hybridRetrievalService;
    }

    @PostMapping("/search")
    public SimilaritySearchResponse search(@RequestBody SimilaritySearchRequest request) {
        return this.retrievalService.search(request);
    }

    /** Inspection endpoint showing the exact hybrid chunks later supplied to Gemini. */
    @PostMapping("/hybrid")
    public MultiDocumentSimilaritySearchResponse hybrid(
            @RequestBody SimilaritySearchRequest request) {
        return this.hybridRetrievalService.searchAcrossDocuments(
                request.question(), Collections.singletonList(request.documentId()), request.topK()
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String invalidRequest(IllegalArgumentException exception) {
        return exception.getMessage();
    }
}
