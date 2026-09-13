package com.libraryai.ai;

/**
 * The HTTP request contract. chatId identifies one book's conversation, while
 * message contains the new question for that book.
 */
public record ChatRequest(String chatId, String message) {
}
