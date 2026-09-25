package com.libraryai.rag;

/** Minimal persisted message used to rebuild a Gemini prompt. */
public record StoredChatMessage(String role, String text) {
}
