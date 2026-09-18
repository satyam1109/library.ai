package com.libraryai.rag;

/** Selects one document as context for a grounded Gemini answer. */
public record RagAnswerRequest(
        String documentId,
        String question,
        Integer topK) {
}
