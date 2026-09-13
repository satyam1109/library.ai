package com.libraryai.ai;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Translates HTTP/JSON input into an application service call. It intentionally
 * contains no Gemini-specific configuration or SDK code.
 */
@RestController
@RequestMapping("/api/ai")
public class AiChatController {

    private final AiChatService aiChatService;

    public AiChatController(AiChatService aiChatService) {
        this.aiChatService = aiChatService;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        try {
            AiChatResult result = aiChatService.generateAnswer(request.chatId(), request.message());

            return new ChatResponse(
                    result.answer(),
                    result.promptTokens(),
                    result.completionTokens(),
                    result.totalTokens()
            );
        } catch (IllegalArgumentException exception) {
            // Convert local input validation into a clear HTTP 400 response.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    /**
     * Clears only the conversation associated with one book/chat ID.
     */
    @DeleteMapping("/chat/{chatId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearHistory(@PathVariable String chatId) {
        try {
            aiChatService.clearHistory(chatId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }
}
