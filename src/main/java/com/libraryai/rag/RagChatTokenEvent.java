package com.libraryai.rag;

/** One incremental piece of the answer produced by Gemini. */
public record RagChatTokenEvent(String text) {
}
