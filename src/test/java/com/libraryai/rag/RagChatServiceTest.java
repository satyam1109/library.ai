package com.libraryai.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagChatServiceTest {

    private static final UUID CHAT_ID = UUID.fromString("923e4567-e89b-12d3-a456-426614174000");
    private static final String FIRST_DOCUMENT_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String SECOND_DOCUMENT_ID = "223e4567-e89b-12d3-a456-426614174000";

    private final HybridRetrievalService retrievalService = mock(HybridRetrievalService.class);
    private final LibraryChatRepository chatRepository = mock(LibraryChatRepository.class);
    private final ConversationMemoryService conversationMemoryService =
            mock(ConversationMemoryService.class);
    private final ChatModel chatModel = mock(ChatModel.class);
    private final RagChatService service = new RagChatService(
            retrievalService, chatRepository, conversationMemoryService, chatModel
    );

    @Test
    void retrievesAcrossSelectedDocumentsAndRetainsIsolatedConversationHistory() {
        when(chatRepository.findContext(CHAT_ID)).thenReturn(new LibraryChatContext(
                CHAT_ID.toString(), "Policy chat", 2,
                List.of(FIRST_DOCUMENT_ID, SECOND_DOCUMENT_ID)
        ));
        when(conversationMemoryService.prepare(CHAT_ID, 2)).thenReturn(
                new PreparedConversationMemory(null, List.of(
                        new StoredChatMessage(1, "USER", "Compare the policies"),
                        new StoredChatMessage(2, "ASSISTANT", "What aspect should I compare?")
                ), null, null, null)
        );
        SimilaritySearchResult firstSource = source(1, FIRST_DOCUMENT_ID, "first.pdf");
        SimilaritySearchResult secondSource = source(2, SECOND_DOCUMENT_ID, "second.pdf");
        when(retrievalService.searchAcrossDocuments(anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    QueryEmbeddingProgressContext.embeddingReady();
                    return new MultiDocumentSimilaritySearchResponse(
                        "retrieval query",
                        List.of(FIRST_DOCUMENT_ID, SECOND_DOCUMENT_ID),
                        5,
                        2,
                        List.of(firstSource, secondSource)
                    );
                });
        ChatResponse modelResponse = modelResponse(
                "The documents differ. [Source 1] [Source 2]"
        );
        when(chatModel.call(any(Prompt.class))).thenReturn(modelResponse);

        List<RagChatProgressStage> stages = new ArrayList<>();
        RagChatResponse response = service.chat(
                CHAT_ID,
                new ChatMessageRequest("What about leave?", 5),
                stages::add
        );

        assertThat(response.documentIds()).containsExactly(FIRST_DOCUMENT_ID, SECOND_DOCUMENT_ID);
        assertThat(response.sources()).containsExactly(firstSource, secondSource);
        assertThat(response.answer()).contains("[Source 1]").contains("[Source 2]");
        assertThat(stages).containsExactly(
                RagChatProgressStage.UNDERSTANDING,
                RagChatProgressStage.SEARCHING,
                RagChatProgressStage.GENERATING
        );

        ArgumentCaptor<String> retrievalQuery = ArgumentCaptor.forClass(String.class);
        verify(retrievalService).searchAcrossDocuments(
                retrievalQuery.capture(),
                eq("What about leave?"),
                org.mockito.ArgumentMatchers.eq(List.of(FIRST_DOCUMENT_ID, SECOND_DOCUMENT_ID)),
                org.mockito.ArgumentMatchers.eq(5)
        );
        assertThat(retrievalQuery.getValue())
                .contains("Compare the policies")
                .contains("What about leave?");

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions().toString())
                .contains("first.pdf")
                .contains("second.pdf")
                .contains("What about leave?")
                .contains("moderately detailed teaching answer")
                .contains("GitHub-Flavored Markdown");
        verify(chatRepository).saveTurn(
                eq(CHAT_ID), eq(2), eq("What about leave?"),
                eq("The documents differ. [Source 1] [Source 2]"),
                eq(List.of(firstSource, secondSource)), eq(100), eq(25), eq(125)
        );
    }

    @Test
    void rejectsAChatWithoutAttachedDocumentsBeforeRetrieval() {
        when(chatRepository.findContext(CHAT_ID)).thenReturn(new LibraryChatContext(
                CHAT_ID.toString(), "Empty chat", 1, List.of()
        ));

        assertThatThrownBy(() -> service.chat(
                CHAT_ID, new ChatMessageRequest("question", 5)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Attach at least one document");

        verify(retrievalService, never()).searchAcrossDocuments(
                anyString(), anyString(), any(), any()
        );
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void streamsSourcesAndAnswerFragmentsWhileRetainingTheCompletedAnswer() {
        when(chatRepository.findContext(CHAT_ID)).thenReturn(new LibraryChatContext(
                CHAT_ID.toString(), "DI chat", 1, List.of(FIRST_DOCUMENT_ID)
        ));
        when(conversationMemoryService.prepare(CHAT_ID, 1)).thenReturn(
                new PreparedConversationMemory(null, List.of(), null, null, null)
        );
        SimilaritySearchResult source = source(1, FIRST_DOCUMENT_ID, "first.pdf");
        when(retrievalService.searchAcrossDocuments(anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    QueryEmbeddingProgressContext.embeddingReady();
                    return new MultiDocumentSimilaritySearchResponse(
                            "question", List.of(FIRST_DOCUMENT_ID), 5, 1, List.of(source)
                    );
                });
        ChatResponse firstChunk = modelResponse("Dependency injection ");
        ChatResponse secondChunk = modelResponse("supplies dependencies. [Source 1]");
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                firstChunk, secondChunk
        ));

        List<SimilaritySearchResult> streamedSources = new ArrayList<>();
        List<String> streamedTokens = new ArrayList<>();
        RagChatResponse response = service.streamChat(
                CHAT_ID,
                new ChatMessageRequest("What is DI?", 5),
                RagChatProgressListener.NONE,
                streamedSources::addAll,
                streamedTokens::add
        );

        assertThat(streamedSources).containsExactly(source);
        assertThat(streamedTokens).containsExactly(
                "Dependency injection ", "supplies dependencies. [Source 1]"
        );
        assertThat(response.answer())
                .isEqualTo("Dependency injection supplies dependencies. [Source 1]");
        verify(chatRepository).saveTurn(
                eq(CHAT_ID), eq(1), eq("What is DI?"),
                eq("Dependency injection supplies dependencies. [Source 1]"),
                eq(List.of(source)), eq(100), eq(25), eq(125)
        );
    }

    @Test
    void includesOlderSummaryAndRecentMessagesAndCountsSummaryTokens() {
        when(chatRepository.findContext(CHAT_ID)).thenReturn(new LibraryChatContext(
                CHAT_ID.toString(), "Long chat", 3, List.of(FIRST_DOCUMENT_ID)
        ));
        when(conversationMemoryService.prepare(CHAT_ID, 3)).thenReturn(
                new PreparedConversationMemory(
                        "The user is comparing bean lifecycle behavior.",
                        List.of(new StoredChatMessage(
                                21, "USER", "Focus on proxy creation next."
                        )),
                        40, 10, 50
                )
        );
        SimilaritySearchResult source = source(1, FIRST_DOCUMENT_ID, "first.pdf");
        when(retrievalService.searchAcrossDocuments(anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    QueryEmbeddingProgressContext.embeddingReady();
                    return new MultiDocumentSimilaritySearchResponse(
                            "query", List.of(FIRST_DOCUMENT_ID), 5, 1, List.of(source)
                    );
                });
        ChatResponse responseWithUsage = modelResponse(
                "Proxy creation happens here. [Source 1]"
        );
        when(chatModel.call(any(Prompt.class))).thenReturn(responseWithUsage);

        RagChatResponse response = service.chat(
                CHAT_ID, new ChatMessageRequest("How does that happen?", 5)
        );

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions().toString())
                .contains("The user is comparing bean lifecycle behavior.")
                .contains("Focus on proxy creation next.")
                .contains("How does that happen?")
                .contains("not document evidence");
        assertThat(response.promptTokens()).isEqualTo(140);
        assertThat(response.completionTokens()).isEqualTo(35);
        assertThat(response.totalTokens()).isEqualTo(175);
        verify(chatRepository).saveTurn(
                eq(CHAT_ID), eq(3), eq("How does that happen?"),
                eq("Proxy creation happens here. [Source 1]"),
                eq(List.of(source)), eq(140), eq(35), eq(175)
        );
    }

    private SimilaritySearchResult source(int rank, String documentId, String fileName) {
        return new SimilaritySearchResult(
                rank,
                0.9 - (rank * 0.1),
                "Relevant source text from " + fileName,
                Map.of(
                        "document_id", documentId,
                        "source_file_name", fileName,
                        "section_title", "Policy",
                        "page_numbers", List.of(rank)
                )
        );
    }

    private ChatResponse modelResponse(String answer) {
        ChatResponse response = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        Usage usage = mock(Usage.class);
        when(response.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(AssistantMessage.builder().content(answer).build());
        when(response.getMetadata()).thenReturn(metadata);
        when(metadata.getUsage()).thenReturn(usage);
        when(usage.getPromptTokens()).thenReturn(100);
        when(usage.getCompletionTokens()).thenReturn(25);
        when(usage.getTotalTokens()).thenReturn(125);
        return response;
    }
}
