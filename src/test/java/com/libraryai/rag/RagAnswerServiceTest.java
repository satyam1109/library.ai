package com.libraryai.rag;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagAnswerServiceTest {

    private static final String DOCUMENT_ID = "123e4567-e89b-12d3-a456-426614174000";

    private final SimilarityRetrievalService retrievalService = mock(SimilarityRetrievalService.class);
    private final DocumentCatalogRepository documentCatalog = mock(DocumentCatalogRepository.class);
    private final ChatModel chatModel = mock(ChatModel.class);
    private final RagAnswerService service = new RagAnswerService(
            retrievalService, documentCatalog, chatModel
    );

    @Test
    void groundsGeminiPromptWithOnlyTheSelectedDocumentsRetrievedSources() {
        UUID documentId = UUID.fromString(DOCUMENT_ID);
        SimilaritySearchResult source = new SimilaritySearchResult(
                1,
                0.91,
                "HashMap stores entries in hash-derived buckets.",
                Map.of(
                        "document_id", DOCUMENT_ID,
                        "section_title", "18. HashMap Internals",
                        "page_numbers", List.of(18)
                )
        );
        when(documentCatalog.isReady(documentId)).thenReturn(true);
        when(retrievalService.search(any())).thenReturn(
                new SimilaritySearchResponse(
                        "How does HashMap work?", DOCUMENT_ID, 3, 1, List.of(source)
                )
        );

        ChatResponse modelResponse = modelResponse("It uses hash-derived buckets. [Source 1]");
        when(chatModel.call(any(Prompt.class))).thenReturn(modelResponse);

        RagAnswerResponse response = service.answer(
                new RagAnswerRequest(DOCUMENT_ID, "How does HashMap work?", 3)
        );

        assertThat(response.documentId()).isEqualTo(DOCUMENT_ID);
        assertThat(response.answer()).contains("[Source 1]");
        assertThat(response.sources()).containsExactly(source);
        assertThat(response.promptTokens()).isEqualTo(120);
        assertThat(response.completionTokens()).isEqualTo(20);
        assertThat(response.totalTokens()).isEqualTo(140);

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions().toString())
                .contains("How does HashMap work?")
                .contains("18. HashMap Internals")
                .contains("hash-derived buckets");
    }

    @Test
    void rejectsUnknownOrUnreadyDocumentBeforeRetrievalAndGeneration() {
        assertThatThrownBy(() -> service.answer(
                new RagAnswerRequest(DOCUMENT_ID, "question", 5)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found or is not ready");

        verify(retrievalService, never()).search(any());
        verify(chatModel, never()).call(any(Prompt.class));
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
        when(usage.getPromptTokens()).thenReturn(120);
        when(usage.getCompletionTokens()).thenReturn(20);
        when(usage.getTotalTokens()).thenReturn(140);
        return response;
    }
}
