package com.libraryai.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationMemoryServiceTest {

    private static final UUID CHAT_ID = UUID.fromString("923e4567-e89b-12d3-a456-426614174000");

    private final LibraryChatRepository chatRepository = mock(LibraryChatRepository.class);
    private final ChatModel chatModel = mock(ChatModel.class);
    private final ConversationMemoryService service = new ConversationMemoryService(
            chatRepository, chatModel
    );

    @Test
    void keepsShortHistoryVerbatimWithoutCallingGemini() {
        List<StoredChatMessage> messages = messages(1, 12);
        when(chatRepository.findConversationMemory(CHAT_ID, 2)).thenReturn(
                new ConversationMemoryState("Earlier context", 50, messages)
        );

        PreparedConversationMemory result = service.prepare(CHAT_ID, 2);

        assertThat(result.summary()).isEqualTo("Earlier context");
        assertThat(result.recentMessages()).containsExactlyElementsOf(messages);
        assertThat(result.totalTokens()).isNull();
        verify(chatModel, never()).call(any(Prompt.class));
        verify(chatRepository, never()).saveConversationSummary(any(), anyInt(), any(), anyLong());
    }

    @Test
    void summarizesOlderMessagesAndKeepsLatestEightVerbatim() {
        List<StoredChatMessage> messages = messages(101, 14);
        when(chatRepository.findConversationMemory(CHAT_ID, 4)).thenReturn(
                new ConversationMemoryState("Existing summary", 100, messages)
        );
        ChatResponse responseWithSummary = summaryResponse();
        when(chatModel.call(any(Prompt.class))).thenReturn(responseWithSummary);

        PreparedConversationMemory result = service.prepare(CHAT_ID, 4);

        assertThat(result.summary()).isEqualTo("Updated rolling summary");
        assertThat(result.recentMessages())
                .extracting(StoredChatMessage::messageOrder)
                .containsExactly(107L, 108L, 109L, 110L, 111L, 112L, 113L, 114L);
        assertThat(result.promptTokens()).isEqualTo(30);
        assertThat(result.completionTokens()).isEqualTo(8);
        assertThat(result.totalTokens()).isEqualTo(38);
        verify(chatRepository).saveConversationSummary(
                CHAT_ID, 4, "Updated rolling summary", 106L
        );

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        String instructions = prompt.getValue().getInstructions().toString();
        assertThat(instructions)
                .contains("Existing summary")
                .contains("message 101")
                .contains("message 106")
                .doesNotContain("message 107")
                .contains("untrusted data")
                .contains("Do not answer");
    }

    private List<StoredChatMessage> messages(long firstOrder, int count) {
        List<StoredChatMessage> messages = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            long order = firstOrder + index;
            messages.add(new StoredChatMessage(
                    order,
                    index % 2 == 0 ? "USER" : "ASSISTANT",
                    "message " + order
            ));
        }
        return messages;
    }

    private ChatResponse summaryResponse() {
        ChatResponse response = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        Usage usage = mock(Usage.class);
        when(response.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(
                AssistantMessage.builder().content("Updated rolling summary").build()
        );
        when(response.getMetadata()).thenReturn(metadata);
        when(metadata.getUsage()).thenReturn(usage);
        when(usage.getPromptTokens()).thenReturn(30);
        when(usage.getCompletionTokens()).thenReturn(8);
        when(usage.getTotalTokens()).thenReturn(38);
        return response;
    }
}
