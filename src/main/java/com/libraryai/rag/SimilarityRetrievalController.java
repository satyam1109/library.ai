package com.libraryai.rag;

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

    public SimilarityRetrievalController(SimilarityRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @PostMapping("/search")
    public SimilaritySearchResponse search(@RequestBody SimilaritySearchRequest request) {
        return this.retrievalService.search(request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String invalidRequest(IllegalArgumentException exception) {
        return exception.getMessage();
    }
}
