package com.libraryai.ai;

import java.util.List;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

/**
 * Owns communication with the AI model. The service depends on Spring AI's
 * provider-neutral ChatModel interface rather than a Google-specific class.
 */
@Service
public class AiChatService {

    private final ChatModel chatModel;
    private final ChatMemory chatMemory;

    /**
     * Spring Boot auto-configures a Gemini-backed ChatModel after finding the
     * Google GenAI starter and API-key configuration.
     */
    public AiChatService(ChatModel chatModel, ChatMemory chatMemory) {
        this.chatModel = chatModel;
        this.chatMemory = chatMemory;
    }

    public AiChatResult generateAnswer(String chatId, String message) {
        validateInput(chatId, message);

        // Spring AI represents every participant's text through the Message interface.
        UserMessage userMessage = UserMessage.builder()
                .text(message)
                .build();

        // chatId is the conversation key. Each book therefore gets separate memory.
        chatMemory.add(chatId, userMessage);

        // The prompt contains this book's retained USER and ASSISTANT messages.
        List<Message> messages = chatMemory.get(chatId);
        ChatResponse response = chatModel.call(new Prompt(messages));

        // Saving Gemini's reply lets a later question refer to the earlier answer.
        AssistantMessage assistantMessage = response.getResult().getOutput();
        chatMemory.add(chatId, assistantMessage);

        // Usage belongs to this specific Gemini call. Prompt tokens include the
        // retained conversation history that was sent again in the Prompt.
        Usage usage = response.getMetadata().getUsage();

        return new AiChatResult(
                assistantMessage.getText(),
                usage.getPromptTokens(),
                usage.getCompletionTokens(),
                usage.getTotalTokens()
        );
    }

    public void clearHistory(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            throw new IllegalArgumentException("Chat ID must not be blank");
        }

        chatMemory.clear(chatId);
    }

    private void validateInput(String chatId, String message) {
        if (chatId == null || chatId.isBlank()) {
            throw new IllegalArgumentException("Chat ID must not be blank");
        }

        if (message == null || message.isBlank()) {
            // Avoid spending an external API call on input that cannot be useful.
            throw new IllegalArgumentException("Message must not be blank");
        }
    }
}
