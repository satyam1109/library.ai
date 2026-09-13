package com.libraryai.ai;

/**
 * The HTTP response contract. It exposes the generated answer and usage for this
 * Gemini call so clients can observe how conversation history affects token cost.
 */
public record ChatResponse(
        String answer,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens
) {
}
