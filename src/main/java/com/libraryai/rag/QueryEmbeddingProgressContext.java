package com.libraryai.rag;

import java.util.function.Supplier;

/**
 * Bridges the query-embedding boundary hidden inside Spring AI's VectorStore.
 * The listener is request-thread scoped and always removed after retrieval.
 */
final class QueryEmbeddingProgressContext {

    private static final ThreadLocal<Runnable> EMBEDDING_READY = new ThreadLocal<>();

    private QueryEmbeddingProgressContext() {
    }

    static <T> T withListener(Runnable listener, Supplier<T> operation) {
        EMBEDDING_READY.set(listener);
        try {
            return operation.get();
        } finally {
            EMBEDDING_READY.remove();
        }
    }

    static void embeddingReady() {
        Runnable listener = EMBEDDING_READY.get();
        if (listener != null) {
            listener.run();
        }
    }
}
