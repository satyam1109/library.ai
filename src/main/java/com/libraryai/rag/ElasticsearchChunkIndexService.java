package com.libraryai.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.libraryai.document.LibraryDocumentMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Maintains an idempotent Elasticsearch BM25 index derived from pgvector chunks.
 * Elasticsearch is deliberately secondary: failures fall back to vector-only search.
 */
@Service
public class ElasticsearchChunkIndexService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticsearchChunkIndexService.class);
    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() { };

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final RetrievalProperties retrievalProperties;
    private final ElasticsearchProperties properties;
    private final AtomicBoolean indexReady = new AtomicBoolean();

    public ElasticsearchChunkIndexService(
            @Qualifier("elasticsearchRestClient") RestClient restClient,
            JdbcTemplate jdbcTemplate,
            RetrievalProperties retrievalProperties,
            ElasticsearchProperties properties) {
        this.restClient = restClient;
        this.objectMapper = new ObjectMapper();
        this.jdbcTemplate = jdbcTemplate;
        this.retrievalProperties = retrievalProperties;
        this.properties = properties;
    }

    /** Replaces one document's lexical chunks without affecting successful ingestion. */
    public void replaceDocumentSafely(String documentId, List<Document> chunks) {
        if (!this.properties.enabled()) {
            return;
        }
        try {
            ensureIndex();
            deleteDocument(documentId);
            bulkIndex(chunks);
        } catch (RuntimeException exception) {
            this.indexReady.set(false);
            LOGGER.warn("Elasticsearch indexing is unavailable; pgvector ingestion remains valid: {}",
                    exception.getMessage());
        }
    }

    /** Searches only the selected chat documents and returns BM25-ranked chunks. */
    public List<KeywordSearchResult> search(
            String question,
            List<String> documentIds,
            int candidateCount) {
        if (!this.properties.enabled()) {
            return List.of();
        }
        try {
            ensureIndex();
            backfillMissingDocuments(documentIds);
            Map<String, Object> request = Map.of(
                    "size", candidateCount,
                    "_source", List.of("chunk_id", "content", "metadata"),
                    "query", Map.of("bool", Map.of(
                            "filter", List.of(Map.of("terms", Map.of("document_id", documentIds))),
                            "must", List.of(Map.of("multi_match", Map.of(
                                    "query", question,
                                    "type", "best_fields",
                                    "operator", "and",
                                    "fields", List.of(
                                            "section_title^4",
                                            "subsection_titles^3",
                                            "heading_path^2",
                                            "content"
                                    )
                            )))
                    ))
            );
            String response = this.restClient.post()
                    .uri("/{index}/_search", this.properties.indexName())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(writeJson(request))
                    .retrieve()
                    .body(String.class);
            return parseHits(readJson(response));
        } catch (RuntimeException exception) {
            this.indexReady.set(false);
            LOGGER.warn("Elasticsearch keyword search is unavailable; using vector results only: {}",
                    exception.getMessage());
            return List.of();
        }
    }

    private void ensureIndex() {
        if (this.indexReady.get()) {
            return;
        }
        synchronized (this.indexReady) {
            if (this.indexReady.get()) {
                return;
            }
            boolean exists;
            try {
                this.restClient.method(HttpMethod.HEAD)
                        .uri("/{index}", this.properties.indexName())
                        .retrieve()
                        .toBodilessEntity();
                exists = true;
            } catch (HttpClientErrorException.NotFound exception) {
                exists = false;
            }
            if (!exists) {
                createIndex();
            }
            this.indexReady.set(true);
        }
    }

    private void createIndex() {
        Map<String, Object> textWithKeyword = Map.of(
                "type", "text",
                "analyzer", "library_text",
                "fields", Map.of("keyword", Map.of("type", "keyword", "ignore_above", 512))
        );
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("chunk_id", Map.of("type", "keyword"));
        fields.put("document_id", Map.of("type", "keyword"));
        fields.put("content", Map.of("type", "text", "analyzer", "library_text"));
        fields.put("section_title", textWithKeyword);
        fields.put("subsection_titles", Map.of("type", "text", "analyzer", "library_text"));
        fields.put("heading_path", Map.of("type", "text", "analyzer", "library_text"));
        fields.put("source_file_name", textWithKeyword);
        fields.put("page_numbers", Map.of("type", "integer"));
        fields.put("chunk_index", Map.of("type", "integer"));
        fields.put("metadata", Map.of("type", "object", "enabled", false));

        this.restClient.put()
                .uri("/{index}", this.properties.indexName())
                .contentType(MediaType.APPLICATION_JSON)
                .body(writeJson(Map.of(
                        "settings", Map.of(
                                "number_of_replicas", 0,
                                "analysis", Map.of(
                                        "filter", Map.of("library_english_stop", Map.of(
                                                "type", "stop", "stopwords", "_english_"
                                        )),
                                        "analyzer", Map.of("library_text", Map.of(
                                                "type", "custom",
                                                "tokenizer", "standard",
                                                "filter", List.of(
                                                        "lowercase", "library_english_stop"
                                                )
                                        ))
                                )
                        ),
                        "mappings", Map.of("dynamic", "strict", "properties", fields)
                )))
                .retrieve()
                .toBodilessEntity();
    }

    private void backfillMissingDocuments(List<String> documentIds) {
        for (String documentId : documentIds) {
            int postgresCount = postgresCount(documentId);
            if (postgresCount > 0 && elasticsearchCount(documentId) != postgresCount) {
                replaceDocument(documentId, loadFromPostgres(documentId));
            }
        }
    }

    private void replaceDocument(String documentId, List<Document> chunks) {
        deleteDocument(documentId);
        bulkIndex(chunks);
    }

    private void deleteDocument(String documentId) {
        this.restClient.post()
                .uri("/{index}/_delete_by_query?refresh=true&conflicts=proceed",
                        this.properties.indexName())
                .contentType(MediaType.APPLICATION_JSON)
                .body(writeJson(Map.of(
                        "query", Map.of("term", Map.of("document_id", documentId))
                )))
                .retrieve()
                .toBodilessEntity();
    }

    private void bulkIndex(List<Document> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        StringBuilder body = new StringBuilder();
        for (Document chunk : chunks) {
            body.append(writeJson(Map.of("index", Map.of(
                    "_index", this.properties.indexName(), "_id", chunk.getId()
            )))).append('\n');
            body.append(writeJson(indexSource(chunk))).append('\n');
        }
        String responseBody = this.restClient.post()
                .uri("/_bulk?refresh=wait_for")
                .contentType(MediaType.parseMediaType("application/x-ndjson"))
                .body(body.toString())
                .retrieve()
                .body(String.class);
        JsonNode response = readJson(responseBody);
        if (response != null && response.path("errors").asBoolean()) {
            throw new IllegalStateException("Elasticsearch rejected one or more chunk index operations");
        }
    }

    private Map<String, Object> indexSource(Document chunk) {
        Map<String, Object> metadata = chunk.getMetadata();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("chunk_id", chunk.getId());
        source.put("document_id", metadata.get(LibraryDocumentMetadata.DOCUMENT_ID));
        source.put("content", chunk.getText());
        copyMetadata(metadata, source, LibraryDocumentMetadata.SECTION_TITLE);
        copyMetadata(metadata, source, LibraryDocumentMetadata.SUBSECTION_TITLES);
        copyMetadata(metadata, source, LibraryDocumentMetadata.HEADING_PATH);
        copyMetadata(metadata, source, LibraryDocumentMetadata.SOURCE_FILE_NAME);
        copyMetadata(metadata, source, LibraryDocumentMetadata.PAGE_NUMBERS);
        copyMetadata(metadata, source, LibraryDocumentMetadata.CHUNK_INDEX);
        source.put("metadata", metadata);
        return source;
    }

    private void copyMetadata(Map<String, Object> metadata, Map<String, Object> target, String key) {
        Object value = metadata.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private int postgresCount(String documentId) {
        Integer count = this.jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + qualifiedVectorTable()
                        + " WHERE metadata->>'document_id' = ?",
                Integer.class,
                documentId
        );
        return count == null ? 0 : count;
    }

    private int elasticsearchCount(String documentId) {
        String responseBody = this.restClient.post()
                .uri("/{index}/_count", this.properties.indexName())
                .contentType(MediaType.APPLICATION_JSON)
                .body(writeJson(Map.of(
                        "query", Map.of("term", Map.of("document_id", documentId))
                )))
                .retrieve()
                .body(String.class);
        JsonNode response = readJson(responseBody);
        return response == null ? 0 : response.path("count").asInt();
    }

    private List<Document> loadFromPostgres(String documentId) {
        return this.jdbcTemplate.query(
                "SELECT id::text, content, metadata::text FROM " + qualifiedVectorTable()
                        + " WHERE metadata->>'document_id' = ? ORDER BY id",
                (resultSet, rowNumber) -> new Document(
                        resultSet.getString(1),
                        resultSet.getString(2),
                        readMetadata(resultSet.getString(3))
                ),
                documentId
        );
    }

    private List<KeywordSearchResult> parseHits(JsonNode response) {
        if (response == null) {
            return List.of();
        }
        List<KeywordSearchResult> results = new ArrayList<>();
        for (JsonNode hit : response.path("hits").path("hits")) {
            JsonNode source = hit.path("_source");
            Map<String, Object> metadata = this.objectMapper.convertValue(
                    source.path("metadata"), METADATA_TYPE
            );
            String chunkId = source.path("chunk_id").asText(hit.path("_id").asText());
            Map<String, Object> enriched = new LinkedHashMap<>(metadata);
            enriched.put("chunk_id", chunkId);
            results.add(new KeywordSearchResult(
                    chunkId,
                    hit.path("_score").asDouble(),
                    source.path("content").asText(),
                    Map.copyOf(enriched)
            ));
        }
        return List.copyOf(results);
    }

    private String qualifiedVectorTable() {
        return this.retrievalProperties.schemaName() + "." + this.retrievalProperties.tableName();
    }

    private String writeJson(Object value) {
        try {
            return this.objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize an Elasticsearch request", exception);
        }
    }

    private Map<String, Object> readMetadata(String value) {
        try {
            return this.objectMapper.readValue(value, METADATA_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not read pgvector chunk metadata", exception);
        }
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) {
            return this.objectMapper.nullNode();
        }
        try {
            return this.objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not parse an Elasticsearch response", exception);
        }
    }
}
