package com.libraryai.rag;

/** Receives progress only when the corresponding backend stage actually begins. */
@FunctionalInterface
public interface RagChatProgressListener {

    RagChatProgressListener NONE = stage -> { };

    void onStage(RagChatProgressStage stage);
}
