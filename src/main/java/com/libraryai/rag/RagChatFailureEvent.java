package com.libraryai.rag;

/** Safe error payload sent through an already-open event stream. */
public record RagChatFailureEvent(RagChatProgressStage stage, String message) {
}
