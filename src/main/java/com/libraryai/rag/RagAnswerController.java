package com.libraryai.rag;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** HTTP endpoint for the full retrieval-then-generation RAG flow. */
@RestController
@RequestMapping("/api/rag")
public class RagAnswerController {

    private final RagAnswerService ragAnswerService;
    private final RagChatService ragChatService;
    private final RagChatStreamService ragChatStreamService;

    public RagAnswerController(
            RagAnswerService ragAnswerService,
            RagChatService ragChatService,
            RagChatStreamService ragChatStreamService) {
        this.ragAnswerService = ragAnswerService;
        this.ragChatService = ragChatService;
        this.ragChatStreamService = ragChatStreamService;
    }

    @PostMapping("/answer")
    public RagAnswerResponse answer(@RequestBody RagAnswerRequest request) {
        return this.ragAnswerService.answer(request);
    }

    @PostMapping("/chat")
    public RagChatResponse chat(@RequestBody RagChatRequest request) {
        return this.ragChatService.chat(request);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody RagChatRequest request) {
        return this.ragChatStreamService.stream(request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String invalidRequest(IllegalArgumentException exception) {
        return exception.getMessage();
    }
}
