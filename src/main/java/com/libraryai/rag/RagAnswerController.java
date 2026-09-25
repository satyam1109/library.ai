package com.libraryai.rag;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** HTTP endpoint for the full retrieval-then-generation RAG flow. */
@RestController
@RequestMapping("/api/rag")
public class RagAnswerController {

    private final RagAnswerService ragAnswerService;

    public RagAnswerController(RagAnswerService ragAnswerService) {
        this.ragAnswerService = ragAnswerService;
    }

    @PostMapping("/answer")
    public RagAnswerResponse answer(@RequestBody RagAnswerRequest request) {
        return this.ragAnswerService.answer(request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String invalidRequest(IllegalArgumentException exception) {
        return exception.getMessage();
    }
}
