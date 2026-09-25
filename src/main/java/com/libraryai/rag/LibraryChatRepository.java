package com.libraryai.rag;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists chats independently from vectors. A chat points at existing parent
 * documents, while messages and the evidence used for each answer remain
 * available when the user returns later.
 */
@Repository
public class LibraryChatRepository {

    private static final int MAX_DOCUMENTS = 3;
    private static final String DEFAULT_TITLE = "New chat";
    private static final TypeReference<Map<String, Object>> METADATA_TYPE =
            new TypeReference<>() { };

    private final JdbcTemplate jdbcTemplate;
    private final RetrievalProperties properties;
    private final DocumentCatalogRepository documentCatalog;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LibraryChatRepository(
            JdbcTemplate jdbcTemplate,
            RetrievalProperties properties,
            DocumentCatalogRepository documentCatalog) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.documentCatalog = documentCatalog;
    }

    @PostConstruct
    void initializeSchema() {
        String schema = this.properties.schemaName();
        this.jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS %s.library_chats (
                    chat_id UUID PRIMARY KEY,
                    title TEXT NOT NULL,
                    context_version INTEGER NOT NULL DEFAULT 1,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """.formatted(schema));
        this.jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS %s.chat_documents (
                    chat_id UUID NOT NULL REFERENCES %s.library_chats(chat_id) ON DELETE CASCADE,
                    document_id UUID NOT NULL REFERENCES %s.library_documents(document_id),
                    attached_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (chat_id, document_id)
                )
                """.formatted(schema, schema, schema));
        this.jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS %s.chat_messages (
                    message_id UUID PRIMARY KEY,
                    message_order BIGSERIAL UNIQUE NOT NULL,
                    chat_id UUID NOT NULL REFERENCES %s.library_chats(chat_id) ON DELETE CASCADE,
                    role VARCHAR(20) NOT NULL,
                    content TEXT NOT NULL,
                    context_version INTEGER NOT NULL,
                    prompt_tokens INTEGER,
                    completion_tokens INTEGER,
                    total_tokens INTEGER,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT chat_message_role CHECK (role IN ('USER', 'ASSISTANT'))
                )
                """.formatted(schema, schema));
        this.jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS %s.chat_message_sources (
                    message_id UUID NOT NULL REFERENCES %s.chat_messages(message_id) ON DELETE CASCADE,
                    source_rank INTEGER NOT NULL,
                    score DOUBLE PRECISION NOT NULL,
                    chunk_text TEXT NOT NULL,
                    metadata JSONB NOT NULL,
                    PRIMARY KEY (message_id, source_rank)
                )
                """.formatted(schema, schema));
        this.jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS %s.chat_conversation_summaries (
                    chat_id UUID NOT NULL REFERENCES %s.library_chats(chat_id) ON DELETE CASCADE,
                    context_version INTEGER NOT NULL,
                    summary TEXT NOT NULL,
                    summarized_through_order BIGINT NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (chat_id, context_version)
                )
                """.formatted(schema, schema));
        this.jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS chat_messages_chat_order_idx
                ON %s.chat_messages (chat_id, message_order)
                """.formatted(schema));
    }

    public LibraryChatDetail create(String requestedTitle) {
        UUID chatId = UUID.randomUUID();
        String title = normalizeTitle(requestedTitle);
        this.jdbcTemplate.update("""
                INSERT INTO %s.library_chats (chat_id, title)
                VALUES (?, ?)
                """.formatted(this.properties.schemaName()), chatId, title);
        return findDetail(chatId);
    }

    public List<LibraryChatSummary> findAll() {
        return this.jdbcTemplate.query("""
                SELECT c.chat_id, c.title, c.context_version, c.created_at, c.updated_at,
                       COUNT(DISTINCT cd.document_id) AS document_count,
                       COUNT(DISTINCT cm.message_id) AS message_count
                FROM %s.library_chats c
                LEFT JOIN %s.chat_documents cd ON cd.chat_id = c.chat_id
                LEFT JOIN %s.chat_messages cm ON cm.chat_id = c.chat_id
                GROUP BY c.chat_id
                ORDER BY c.updated_at DESC
                """.formatted(schema(), schema(), schema()), (resultSet, rowNumber) ->
                new LibraryChatSummary(
                        resultSet.getObject("chat_id", UUID.class).toString(),
                        resultSet.getString("title"),
                        resultSet.getInt("context_version"),
                        resultSet.getInt("document_count"),
                        resultSet.getInt("message_count"),
                        resultSet.getObject("created_at", OffsetDateTime.class),
                        resultSet.getObject("updated_at", OffsetDateTime.class)
                ));
    }

    public LibraryChatDetail findDetail(UUID chatId) {
        ChatRow chat = findChat(chatId);
        return new LibraryChatDetail(
                chat.chatId().toString(),
                chat.title(),
                chat.contextVersion(),
                findDocuments(chatId),
                findMessages(chatId),
                chat.createdAt(),
                chat.updatedAt()
        );
    }

    public LibraryChatDetail rename(UUID chatId, String requestedTitle) {
        if (requestedTitle == null || requestedTitle.isBlank()) {
            throw new IllegalArgumentException("Chat title must not be blank");
        }
        String title = normalizeTitle(requestedTitle);
        int updated = this.jdbcTemplate.update("""
                UPDATE %s.library_chats
                SET title = ?, updated_at = CURRENT_TIMESTAMP
                WHERE chat_id = ?
                """.formatted(schema()), title, chatId);
        if (updated == 0) {
            throw new IllegalArgumentException("Chat was not found: " + chatId);
        }
        return findDetail(chatId);
    }

    public LibraryChatContext findContext(UUID chatId) {
        ChatRow chat = findChat(chatId);
        List<String> documentIds = this.jdbcTemplate.query("""
                SELECT document_id
                FROM %s.chat_documents
                WHERE chat_id = ?
                ORDER BY attached_at, document_id
                """.formatted(schema()),
                (resultSet, rowNumber) -> resultSet.getObject("document_id", UUID.class).toString(),
                chatId
        );
        return new LibraryChatContext(
                chat.chatId().toString(), chat.title(), chat.contextVersion(), documentIds
        );
    }

    @Transactional
    public LibraryChatDetail attachDocuments(UUID chatId, List<String> rawDocumentIds) {
        findChatForUpdate(chatId);
        if (rawDocumentIds == null || rawDocumentIds.isEmpty()) {
            throw new IllegalArgumentException("Select at least one document to attach");
        }

        LinkedHashSet<UUID> requested = new LinkedHashSet<>();
        for (String rawDocumentId : rawDocumentIds) {
            UUID documentId = parseId(rawDocumentId, "documentId");
            if (!requested.add(documentId)) {
                throw new IllegalArgumentException("documentIds must not contain duplicates");
            }
            if (!this.documentCatalog.isReady(documentId)) {
                throw new IllegalArgumentException(
                        "documentId was not found or is not ready: " + documentId
                );
            }
        }

        List<UUID> existing = attachedDocumentIds(chatId);
        LinkedHashSet<UUID> combined = new LinkedHashSet<>(existing);
        combined.addAll(requested);
        if (combined.size() > MAX_DOCUMENTS) {
            throw new IllegalArgumentException("A chat can contain at most 3 documents");
        }

        int inserted = 0;
        for (UUID documentId : requested) {
            inserted += this.jdbcTemplate.update("""
                    INSERT INTO %s.chat_documents (chat_id, document_id)
                    VALUES (?, ?)
                    ON CONFLICT DO NOTHING
                    """.formatted(schema()), chatId, documentId);
        }
        if (inserted > 0) {
            bumpContextVersion(chatId);
        }
        return findDetail(chatId);
    }

    @Transactional
    public LibraryChatDetail detachDocument(UUID chatId, UUID documentId) {
        findChatForUpdate(chatId);
        int removed = this.jdbcTemplate.update("""
                DELETE FROM %s.chat_documents
                WHERE chat_id = ? AND document_id = ?
                """.formatted(schema()), chatId, documentId);
        if (removed > 0) {
            bumpContextVersion(chatId);
        }
        return findDetail(chatId);
    }

    public ConversationMemoryState findConversationMemory(UUID chatId, int contextVersion) {
        List<SummaryRow> summaries = this.jdbcTemplate.query("""
                SELECT summary, summarized_through_order
                FROM %s.chat_conversation_summaries
                WHERE chat_id = ? AND context_version = ?
                """.formatted(schema()), (resultSet, rowNumber) -> new SummaryRow(
                        resultSet.getString("summary"),
                        resultSet.getLong("summarized_through_order")
                ), chatId, contextVersion);
        SummaryRow summary = summaries.isEmpty() ? new SummaryRow(null, 0L) : summaries.getFirst();

        List<StoredChatMessage> messages = this.jdbcTemplate.query("""
                SELECT message_order, role, content
                FROM %s.chat_messages
                WHERE chat_id = ? AND context_version = ? AND message_order > ?
                ORDER BY message_order
                """.formatted(schema()), (resultSet, rowNumber) -> new StoredChatMessage(
                        resultSet.getLong("message_order"),
                        resultSet.getString("role"),
                        resultSet.getString("content")
                ), chatId, contextVersion, summary.summarizedThroughOrder());
        return new ConversationMemoryState(
                summary.summary(), summary.summarizedThroughOrder(), messages
        );
    }

    public void saveConversationSummary(
            UUID chatId,
            int contextVersion,
            String summary,
            long summarizedThroughOrder) {
        this.jdbcTemplate.update("""
                INSERT INTO %s.chat_conversation_summaries (
                    chat_id, context_version, summary, summarized_through_order
                ) VALUES (?, ?, ?, ?)
                ON CONFLICT (chat_id, context_version) DO UPDATE
                SET summary = EXCLUDED.summary,
                    summarized_through_order = EXCLUDED.summarized_through_order,
                    updated_at = CURRENT_TIMESTAMP
                """.formatted(schema()),
                chatId, contextVersion, summary, summarizedThroughOrder);
    }

    @Transactional
    public void saveTurn(
            UUID chatId,
            int expectedContextVersion,
            String userText,
            String assistantText,
            List<SimilaritySearchResult> sources,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens) {
        ChatRow current = findChatForUpdate(chatId);
        if (current.contextVersion() != expectedContextVersion) {
            throw new IllegalStateException(
                    "The chat documents changed while the answer was being created. Please ask again."
            );
        }

        insertMessage(
                UUID.randomUUID(), chatId, "USER", userText, expectedContextVersion,
                null, null, null
        );
        UUID assistantMessageId = UUID.randomUUID();
        insertMessage(
                assistantMessageId, chatId, "ASSISTANT", assistantText,
                expectedContextVersion, promptTokens, completionTokens, totalTokens
        );
        for (SimilaritySearchResult source : sources) {
            this.jdbcTemplate.update("""
                    INSERT INTO %s.chat_message_sources (
                        message_id, source_rank, score, chunk_text, metadata
                    ) VALUES (?, ?, ?, ?, CAST(? AS jsonb))
                    """.formatted(schema()),
                    assistantMessageId,
                    source.rank(),
                    source.score(),
                    source.text(),
                    writeMetadata(source.metadata())
            );
        }

        String firstQuestionTitle = titleFromQuestion(userText);
        this.jdbcTemplate.update("""
                UPDATE %s.library_chats
                SET title = CASE WHEN title = ? THEN ? ELSE title END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE chat_id = ?
                """.formatted(schema()), DEFAULT_TITLE, firstQuestionTitle, chatId);
    }

    private void insertMessage(
            UUID messageId,
            UUID chatId,
            String role,
            String content,
            int contextVersion,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens) {
        this.jdbcTemplate.update("""
                INSERT INTO %s.chat_messages (
                    message_id, chat_id, role, content, context_version,
                    prompt_tokens, completion_tokens, total_tokens
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(schema()),
                messageId, chatId, role, content, contextVersion,
                promptTokens, completionTokens, totalTokens
        );
    }

    private List<LibraryDocumentSummary> findDocuments(UUID chatId) {
        return this.jdbcTemplate.query("""
                SELECT d.document_id, d.original_file_name, d.content_fingerprint,
                       d.status, d.chunk_count, d.created_at, d.updated_at
                FROM %s.chat_documents cd
                JOIN %s.library_documents d ON d.document_id = cd.document_id
                WHERE cd.chat_id = ?
                ORDER BY cd.attached_at, d.document_id
                """.formatted(schema(), schema()),
                (resultSet, rowNumber) -> new LibraryDocumentSummary(
                        resultSet.getObject("document_id", UUID.class).toString(),
                        resultSet.getString("original_file_name"),
                        resultSet.getString("content_fingerprint"),
                        resultSet.getString("status"),
                        resultSet.getInt("chunk_count"),
                        resultSet.getObject("created_at", OffsetDateTime.class),
                        resultSet.getObject("updated_at", OffsetDateTime.class)
                ),
                chatId
        );
    }

    private List<LibraryChatMessage> findMessages(UUID chatId) {
        List<MessageRow> messageRows = this.jdbcTemplate.query("""
                SELECT message_id, role, content, context_version,
                       prompt_tokens, completion_tokens, total_tokens, created_at
                FROM %s.chat_messages
                WHERE chat_id = ?
                ORDER BY message_order
                """.formatted(schema()), (resultSet, rowNumber) -> new MessageRow(
                        resultSet.getObject("message_id", UUID.class),
                        resultSet.getString("role").toLowerCase(),
                        resultSet.getString("content"),
                        resultSet.getInt("context_version"),
                        getNullableInteger(resultSet, "prompt_tokens"),
                        getNullableInteger(resultSet, "completion_tokens"),
                        getNullableInteger(resultSet, "total_tokens"),
                        resultSet.getObject("created_at", OffsetDateTime.class)
                ), chatId);
        return messageRows.stream().map(message -> new LibraryChatMessage(
                message.messageId().toString(),
                message.role(),
                message.content(),
                message.contextVersion(),
                findSources(message.messageId()),
                message.promptTokens(),
                message.completionTokens(),
                message.totalTokens(),
                message.createdAt()
        )).toList();
    }

    private List<SimilaritySearchResult> findSources(UUID messageId) {
        return this.jdbcTemplate.query("""
                SELECT source_rank, score, chunk_text, metadata
                FROM %s.chat_message_sources
                WHERE message_id = ?
                ORDER BY source_rank
                """.formatted(schema()),
                (resultSet, rowNumber) -> new SimilaritySearchResult(
                        resultSet.getInt("source_rank"),
                        resultSet.getDouble("score"),
                        resultSet.getString("chunk_text"),
                        readMetadata(resultSet.getString("metadata"))
                ),
                messageId
        );
    }

    private ChatRow findChat(UUID chatId) {
        List<ChatRow> rows = this.jdbcTemplate.query("""
                SELECT chat_id, title, context_version, created_at, updated_at
                FROM %s.library_chats
                WHERE chat_id = ?
                """.formatted(schema()), this::mapChat, chatId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Chat was not found: " + chatId);
        }
        return rows.getFirst();
    }

    private ChatRow findChatForUpdate(UUID chatId) {
        List<ChatRow> rows = this.jdbcTemplate.query("""
                SELECT chat_id, title, context_version, created_at, updated_at
                FROM %s.library_chats
                WHERE chat_id = ?
                FOR UPDATE
                """.formatted(schema()), this::mapChat, chatId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Chat was not found: " + chatId);
        }
        return rows.getFirst();
    }

    private ChatRow mapChat(java.sql.ResultSet resultSet, int rowNumber)
            throws java.sql.SQLException {
        return new ChatRow(
                resultSet.getObject("chat_id", UUID.class),
                resultSet.getString("title"),
                resultSet.getInt("context_version"),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private List<UUID> attachedDocumentIds(UUID chatId) {
        return this.jdbcTemplate.query("""
                SELECT document_id FROM %s.chat_documents WHERE chat_id = ?
                """.formatted(schema()),
                (resultSet, rowNumber) -> resultSet.getObject("document_id", UUID.class),
                chatId
        );
    }

    private void bumpContextVersion(UUID chatId) {
        this.jdbcTemplate.update("""
                UPDATE %s.library_chats
                SET context_version = context_version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE chat_id = ?
                """.formatted(schema()), chatId);
    }

    private Integer getNullableInteger(java.sql.ResultSet resultSet, String column)
            throws java.sql.SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private String writeMetadata(Map<String, Object> metadata) {
        try {
            return this.objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not store source metadata", exception);
        }
    }

    private Map<String, Object> readMetadata(String metadata) {
        try {
            return this.objectMapper.readValue(metadata, METADATA_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not read source metadata", exception);
        }
    }

    private UUID parseId(String rawId, String label) {
        if (rawId == null || rawId.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        try {
            return UUID.fromString(rawId.strip());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(label + " must be a valid UUID", exception);
        }
    }

    private String normalizeTitle(String requestedTitle) {
        if (requestedTitle == null || requestedTitle.isBlank()) {
            return DEFAULT_TITLE;
        }
        return shorten(requestedTitle.strip().replaceAll("\\s+", " "), 80);
    }

    private String titleFromQuestion(String question) {
        return shorten(question.strip().replaceAll("\\s+", " "), 60);
    }

    private String shorten(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 1).stripTrailing() + "…";
    }

    private String schema() {
        return this.properties.schemaName();
    }

    private record ChatRow(
            UUID chatId,
            String title,
            int contextVersion,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    private record MessageRow(
            UUID messageId,
            String role,
            String content,
            int contextVersion,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            OffsetDateTime createdAt) {
    }

    private record SummaryRow(String summary, long summarizedThroughOrder) {
    }
}
