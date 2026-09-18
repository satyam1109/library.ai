package com.libraryai.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
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
                    match.getMetadata()
            ));
        }

        return new SimilaritySearchResponse(
                question, documentId, topK, results.size(), List.copyOf(results)
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
}
