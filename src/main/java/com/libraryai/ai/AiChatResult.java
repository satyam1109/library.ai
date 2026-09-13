package com.libraryai.ai;

/**
 * Service-level result containing both Gemini's answer and its token usage.
 * Keeping this separate from the HTTP DTO prevents the service from depending
 * on the API's response shape.
 */
public record AiChatResult(
        String answer,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens
) {
}
