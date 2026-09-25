package com.libraryai.rag;

/** One question asked inside an existing Library AI chat. */
public record ChatMessageRequest(String message, Integer topK) {
}
