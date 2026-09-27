package com.libraryai.rag;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LibraryChatRepositoryTest {

    private static final UUID CHAT_ID =
            UUID.fromString("923e4567-e89b-12d3-a456-426614174000");

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final RetrievalProperties properties = new RetrievalProperties(
            "gemini-embedding-001", 768, 5, 20, "public", "vector_store"
    );
    private final LibraryChatRepository repository = new LibraryChatRepository(
            jdbcTemplate, properties, mock(DocumentCatalogRepository.class)
    );

    @Test
    void deletesTheChatRowSoDatabaseCascadesCanCleanUpOwnedData() {
        when(jdbcTemplate.update(anyString(), eq(CHAT_ID))).thenReturn(1);

        repository.delete(CHAT_ID);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), eq(CHAT_ID));
        assertThat(sql.getValue())
                .contains("DELETE FROM public.library_chats")
                .contains("WHERE chat_id = ?");
    }

    @Test
    void rejectsDeletionWhenTheChatDoesNotExist() {
        when(jdbcTemplate.update(anyString(), eq(CHAT_ID))).thenReturn(0);

        assertThatThrownBy(() -> repository.delete(CHAT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Chat was not found: " + CHAT_ID);
    }
}
