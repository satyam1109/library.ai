package com.libraryai.rag;

import java.util.List;

/** Existing documents to attach to a chat. A chat can contain at most three. */
public record AttachChatDocumentsRequest(List<String> documentIds) {
}
