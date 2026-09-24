package com.libraryai.rag;

/** Real processing milestones emitted while a conversational RAG request runs. */
public enum RagChatProgressStage {
    UNDERSTANDING,
    SEARCHING,
    GENERATING
}
