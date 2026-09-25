package com.libraryai.rag;

import org.springframework.ai.embedding.EmbeddingResponse;

/** Thread-local bridge from Spring AI's embedding adapter to one ingestion request. */
final class DocumentEmbeddingProgressContext {

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private DocumentEmbeddingProgressContext() {
    }

    static int track(
            int totalChunks,
            PdfIngestionProgressListener listener,
            Runnable embeddingOperation) {
        State previous = CURRENT.get();
        State current = new State(totalChunks, listener);
        CURRENT.set(current);
        try {
            embeddingOperation.run();
            return current.tokens;
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    static void batchCompleted(int embeddedChunks, EmbeddingResponse response) {
        State state = CURRENT.get();
        if (state == null) {
            return;
        }
        state.completedChunks = Math.min(
                state.totalChunks, state.completedChunks + embeddedChunks
        );
        state.tokens += tokenCount(response);
        state.listener.onProgress(new PdfIngestionProgressEvent(
                PdfIngestionStage.INDEXING,
                "Creating Gemini embeddings and indexing chunks",
                state.completedChunks,
                state.totalChunks,
                state.tokens
        ));
    }

    private static int tokenCount(EmbeddingResponse response) {
        if (response == null || response.getMetadata() == null
                || response.getMetadata().getUsage() == null
                || response.getMetadata().getUsage().getTotalTokens() == null) {
            return 0;
        }
        return response.getMetadata().getUsage().getTotalTokens();
    }

    private static final class State {
        private final int totalChunks;
        private final PdfIngestionProgressListener listener;
        private int completedChunks;
        private int tokens;

        private State(int totalChunks, PdfIngestionProgressListener listener) {
            this.totalChunks = totalChunks;
            this.listener = listener;
        }
    }
}
