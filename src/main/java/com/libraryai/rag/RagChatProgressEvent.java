package com.libraryai.rag;

/** JSON payload for one server-sent progress event. */
public record RagChatProgressEvent(
        RagChatProgressStage stage,
        String message,
        int completedSteps,
        int totalSteps) {

    static RagChatProgressEvent from(RagChatProgressStage stage) {
        return switch (stage) {
            case UNDERSTANDING -> new RagChatProgressEvent(
                    stage, "Understanding your question", 0, 3
            );
            case SEARCHING -> new RagChatProgressEvent(
                    stage, "Searching selected documents", 1, 3
            );
            case GENERATING -> new RagChatProgressEvent(
                    stage, "Creating your grounded answer", 2, 3
            );
        };
    }
}
