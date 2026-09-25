package com.libraryai.rag;

/** Persisted message used to rebuild and compact a Gemini conversation. */
public record StoredChatMessage(long messageOrder, String role, String text) {
}
