package com.libraryai.rag;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagChatServiceTest {

    private static final String CONVERSATION_ID = "923e4567-e89b-12d3-a456-426614174000";
    private static final String FIRST_DOCUMENT_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String SECOND_DOCUMENT_ID = "223e4567-e89b-12d3-a456-426614174000";

    private final SimilarityRetrievalService retrievalService = mock(SimilarityRetrievalService.class);
    private final DocumentCatalogRepository documentCatalog = mock(DocumentCatalogRepository.class);
    private final ChatModel chatModel = mock(ChatModel.class);
    private final ChatMemory chatMemory = mock(ChatMemory.class);
    private final RagChatService service = new RagChatService(
            retrievalService, documentCatalog, chatModel, chatMemory
    );

    @Test
    void retrievesAcrossSelectedDocumentsAndRetainsIsolatedConversationHistory() {
        when(documentCatalog.isReady(any(UUID.class))).thenReturn(true);
        when(chatMemory.get(anyString())).thenReturn(List.of(
                UserMessage.builder().text("Compare the policies").build(),
                AssistantMessage.builder().content("What aspect should I compare?").build()
        ));
        SimilaritySearchResult firstSource = source(1, FIRST_DOCUMENT_ID, "first.pdf");
        SimilaritySearchResult secondSource = source(2, SECOND_DOCUMENT_ID, "second.pdf");
        when(retrievalService.searchAcrossDocuments(anyString(), any(), any()))
                .thenReturn(new MultiDocumentSimilaritySearchResponse(
                        "retrieval query",
                        List.of(FIRST_DOCUMENT_ID, SECOND_DOCUMENT_ID),
                        5,
                        2,
                        List.of(firstSource, secondSource)
                ));
        ChatResponse modelResponse = modelResponse(
                "The documents differ. [Source 1] [Source 2]"
        );
        when(chatModel.call(any(Prompt.class))).thenReturn(modelResponse);

        RagChatResponse response = service.chat(new RagChatRequest(
                CONVERSATION_ID,
                List.of(FIRST_DOCUMENT_ID, SECOND_DOCUMENT_ID),
                "What about leave?",
                5
        ));

        assertThat(response.documentIds()).containsExactly(FIRST_DOCUMENT_ID, SECOND_DOCUMENT_ID);
        assertThat(response.sources()).containsExactly(firstSource, secondSource);
        assertThat(response.answer()).contains("[Source 1]").contains("[Source 2]");

        ArgumentCaptor<String> retrievalQuery = ArgumentCaptor.forClass(String.class);
        verify(retrievalService).searchAcrossDocuments(
                retrievalQuery.capture(),
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
        verify(chatMemory, times(2)).add(anyString(), any(Message.class));
    }

    @Test
    void rejectsMoreThanThreeDocumentsBeforeRetrieval() {
        assertThatThrownBy(() -> service.chat(new RagChatRequest(
                CONVERSATION_ID,
                List.of(
                        FIRST_DOCUMENT_ID,
                        SECOND_DOCUMENT_ID,
                        "323e4567-e89b-12d3-a456-426614174000",
                        "423e4567-e89b-12d3-a456-426614174000"
                ),
                "question",
                5
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 3");

        verify(retrievalService, never()).searchAcrossDocuments(anyString(), any(), any());
        verify(chatModel, never()).call(any(Prompt.class));
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
