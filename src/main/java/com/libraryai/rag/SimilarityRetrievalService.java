package com.libraryai.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

/** Embeds a question in query mode and retrieves the closest stored chunks. */
@Service
public class SimilarityRetrievalService {

    private final VectorStore vectorStore;
    private final RetrievalProperties properties;

    public SimilarityRetrievalService(VectorStore vectorStore, RetrievalProperties properties) {
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    public SimilaritySearchResponse search(SimilaritySearchRequest request) {
        String question = requireQuestion(request.question());
        int topK = validatedTopK(request.topK());
        String documentId = requireDocumentId(request.documentId());

        SearchRequest.Builder search = SearchRequest.builder()
                .query(question)
                .topK(topK)
                .similarityThresholdAll()
                .filterExpression("document_id == '" + documentId + "'");

        List<Document> matches = this.vectorStore.similaritySearch(search.build());
        List<SimilaritySearchResult> results = new ArrayList<>(matches.size());
        for (int index = 0; index < matches.size(); index++) {
            Document match = matches.get(index);
            results.add(new SimilaritySearchResult(
                    index + 1,
                    match.getScore() == null ? 0.0 : match.getScore(),
                    match.getText(),
                    metadataWithChunkId(match)
            ));
        }

        return new SimilaritySearchResponse(
                question, documentId, topK, results.size(), List.copyOf(results)
        );
    }

    /**
     * Runs one query embedding and one globally ranked search across up to three
     * selected documents. The IN filter preserves strict context isolation.
     */
    public MultiDocumentSimilaritySearchResponse searchAcrossDocuments(
            String question,
            List<String> requestedDocumentIds,
            Integer requestedTopK) {
        String validatedQuestion = requireQuestion(question);
        int topK = validatedTopK(requestedTopK);
        List<String> documentIds = requireDocumentIds(requestedDocumentIds);

        var filter = new FilterExpressionBuilder()
                .in("document_id", documentIds.stream().map(value -> (Object) value).toList())
                .build();
        SearchRequest search = SearchRequest.builder()
                .query(validatedQuestion)
                .topK(topK)
                .similarityThresholdAll()
                .filterExpression(filter)
                .build();

        List<SimilaritySearchResult> results = toResults(this.vectorStore.similaritySearch(search));
        return new MultiDocumentSimilaritySearchResponse(
                validatedQuestion,
                documentIds,
                topK,
                results.size(),
                results
        );
    }

    private String requireQuestion(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        return question.strip();
    }

    private int validatedTopK(Integer requestedTopK) {
        int topK = requestedTopK == null ? this.properties.defaultTopK() : requestedTopK;
        if (topK < 1 || topK > this.properties.maxTopK()) {
            throw new IllegalArgumentException(
                    "topK must be between 1 and " + this.properties.maxTopK()
            );
        }
        return topK;
    }

    private String requireDocumentId(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId must not be blank");
        }
        try {
            return UUID.fromString(documentId.strip()).toString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("documentId must be a valid UUID", exception);
        }
    }

    private List<String> requireDocumentIds(List<String> requestedDocumentIds) {
        if (requestedDocumentIds == null || requestedDocumentIds.isEmpty()) {
            throw new IllegalArgumentException("Select at least one document");
        }
        if (requestedDocumentIds.size() > 3) {
            throw new IllegalArgumentException("A chat can use at most 3 documents");
        }

        List<String> documentIds = requestedDocumentIds.stream()
                .map(this::requireDocumentId)
                .distinct()
                .toList();
        if (documentIds.size() != requestedDocumentIds.size()) {
            throw new IllegalArgumentException("documentIds must not contain duplicates");
        }
        return documentIds;
    }

    private List<SimilaritySearchResult> toResults(List<Document> matches) {
        List<SimilaritySearchResult> results = new ArrayList<>(matches.size());
        for (int index = 0; index < matches.size(); index++) {
            Document match = matches.get(index);
            results.add(new SimilaritySearchResult(
                    index + 1,
                    match.getScore() == null ? 0.0 : match.getScore(),
                    match.getText(),
                    metadataWithChunkId(match)
            ));
        }
        return List.copyOf(results);
    }

    private java.util.Map<String, Object> metadataWithChunkId(Document document) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
        metadata.put("chunk_id", document.getId());
        return java.util.Map.copyOf(metadata);
    }
}
