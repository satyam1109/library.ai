package com.libraryai.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

/** Combines pgvector semantic ranking and Elasticsearch BM25 ranking using RRF. */
@Service
public class HybridRetrievalService {

    private static final int SHINGLE_SIZE = 5;

    private final SimilarityRetrievalService vectorRetrieval;
    private final ElasticsearchChunkIndexService keywordRetrieval;
    private final RetrievalProperties retrievalProperties;
    private final ElasticsearchProperties elasticsearchProperties;

    public HybridRetrievalService(
            SimilarityRetrievalService vectorRetrieval,
            ElasticsearchChunkIndexService keywordRetrieval,
            RetrievalProperties retrievalProperties,
            ElasticsearchProperties elasticsearchProperties) {
        this.vectorRetrieval = vectorRetrieval;
        this.keywordRetrieval = keywordRetrieval;
        this.retrievalProperties = retrievalProperties;
        this.elasticsearchProperties = elasticsearchProperties;
    }

    public MultiDocumentSimilaritySearchResponse searchAcrossDocuments(
            String question,
            List<String> documentIds,
            Integer requestedTopK) {
        return searchAcrossDocuments(question, question, documentIds, requestedTopK);
    }

    /**
     * Semantic retrieval may include conversation context, while BM25 should use
     * the user's concise current question so its required technical terms stay precise.
     */
    public MultiDocumentSimilaritySearchResponse searchAcrossDocuments(
            String semanticQuestion,
            String keywordQuestion,
            List<String> documentIds,
            Integer requestedTopK) {
        int topK = requestedTopK == null
                ? this.retrievalProperties.defaultTopK() : requestedTopK;
        if (topK < 1 || topK > this.retrievalProperties.maxTopK()) {
            throw new IllegalArgumentException(
                    "topK must be between 1 and " + this.retrievalProperties.maxTopK()
            );
        }
        int candidateCount = Math.min(
                this.elasticsearchProperties.candidateCount(),
                this.retrievalProperties.maxTopK()
        );
        MultiDocumentSimilaritySearchResponse vectorResponse =
                this.vectorRetrieval.searchAcrossDocuments(
                        semanticQuestion, documentIds, candidateCount
                );
        List<KeywordSearchResult> keywordResults = this.keywordRetrieval.search(
                keywordQuestion, vectorResponse.documentIds(), candidateCount
        );

        if (keywordResults.isEmpty()) {
            List<SimilaritySearchResult> fallback = rerank(
                    vectorResponse.results().stream().limit(topK).toList(), "vector"
            );
            return response(keywordQuestion, vectorResponse, topK, fallback);
        }

        List<SimilaritySearchResult> fused = fuseAndReduce(
                vectorResponse.results(), keywordResults, topK
        );
        return response(keywordQuestion, vectorResponse, topK, fused);
    }

    private List<SimilaritySearchResult> fuseAndReduce(
            List<SimilaritySearchResult> vectorResults,
            List<KeywordSearchResult> keywordResults,
            int topK) {
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        for (int index = 0; index < vectorResults.size(); index++) {
            SimilaritySearchResult result = vectorResults.get(index);
            String chunkId = chunkId(result.metadata(), result.text());
            Candidate candidate = candidates.computeIfAbsent(
                    chunkId,
                    ignored -> Candidate.fromVector(chunkId, result)
            );
            candidate.addVector(index + 1, result.score(), rrf(index + 1));
        }
        for (int index = 0; index < keywordResults.size(); index++) {
            KeywordSearchResult result = keywordResults.get(index);
            Candidate candidate = candidates.computeIfAbsent(
                    result.chunkId(),
                    ignored -> Candidate.fromKeyword(result)
            );
            candidate.addKeyword(index + 1, result.score(), rrf(index + 1));
        }

        List<Candidate> ranked = candidates.values().stream()
                .sorted(Comparator.comparingDouble(Candidate::rrfScore).reversed()
                        .thenComparingInt(Candidate::bestRank)
                        .thenComparing(Candidate::chunkId))
                .toList();

        List<Candidate> selected = new ArrayList<>();
        for (Candidate candidate : ranked) {
            if (selected.stream().noneMatch(existing -> redundant(existing, candidate))) {
                selected.add(candidate);
                if (selected.size() == topK) {
                    break;
                }
            }
        }

        double maximumRrfScore = 2.0 / (this.elasticsearchProperties.rrfRankConstant() + 1.0);
        List<SimilaritySearchResult> results = new ArrayList<>(selected.size());
        for (int index = 0; index < selected.size(); index++) {
            Candidate candidate = selected.get(index);
            Map<String, Object> metadata = candidate.enrichedMetadata();
            double normalizedScore = Math.min(1.0, candidate.rrfScore() / maximumRrfScore);
            results.add(new SimilaritySearchResult(
                    index + 1,
                    normalizedScore,
                    candidate.text(),
                    metadata
            ));
        }
        return List.copyOf(results);
    }

    private double rrf(int rank) {
        return 1.0 / (this.elasticsearchProperties.rrfRankConstant() + rank);
    }

    private boolean redundant(Candidate first, Candidate second) {
        Object firstDocument = first.metadata().get("document_id");
        Object secondDocument = second.metadata().get("document_id");
        if (firstDocument == null || !firstDocument.equals(secondDocument)) {
            return false;
        }

        boolean sameSection = equalNonBlank(
                first.metadata().get("section_title"),
                second.metadata().get("section_title")
        );
        Integer firstIndex = integerValue(first.metadata().get("chunk_index"));
        Integer secondIndex = integerValue(second.metadata().get("chunk_index"));
        boolean adjacent = firstIndex != null && secondIndex != null
                && Math.abs(firstIndex - secondIndex) <= 1;
        if (!sameSection && !adjacent) {
            return false;
        }
        return textOverlap(first.text(), second.text())
                >= this.elasticsearchProperties.redundancyOverlapThreshold();
    }

    private double textOverlap(String first, String second) {
        Set<String> firstShingles = shingles(first);
        Set<String> secondShingles = shingles(second);
        if (firstShingles.isEmpty() || secondShingles.isEmpty()) {
            return normalize(first).equals(normalize(second)) ? 1.0 : 0.0;
        }
        Set<String> shared = new HashSet<>(firstShingles);
        shared.retainAll(secondShingles);
        return shared.size() / (double) Math.min(firstShingles.size(), secondShingles.size());
    }

    private Set<String> shingles(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return Set.of();
        }
        String[] words = normalized.split(" ");
        if (words.length < SHINGLE_SIZE) {
            return Set.of();
        }
        Set<String> shingles = new HashSet<>();
        for (int index = 0; index <= words.length - SHINGLE_SIZE; index++) {
            shingles.add(String.join(" ", java.util.Arrays.copyOfRange(
                    words, index, index + SHINGLE_SIZE
            )));
        }
        return shingles;
    }

    private String normalize(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}_]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private boolean equalNonBlank(Object first, Object second) {
        return first != null && second != null
                && !first.toString().isBlank()
                && first.toString().equals(second.toString());
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String chunkId(Map<String, Object> metadata, String text) {
        Object chunkId = metadata.get("chunk_id");
        if (chunkId != null && !chunkId.toString().isBlank()) {
            return chunkId.toString();
        }
        return metadata.getOrDefault("document_id", "unknown") + ":"
                + metadata.getOrDefault("chunk_fingerprint", Integer.toHexString(text.hashCode()));
    }

    private List<SimilaritySearchResult> rerank(
            List<SimilaritySearchResult> results,
            String retrievalMode) {
        List<SimilaritySearchResult> ranked = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            SimilaritySearchResult result = results.get(index);
            Map<String, Object> metadata = new LinkedHashMap<>(result.metadata());
            metadata.put("retrieval_mode", retrievalMode);
            metadata.put("vector_rank", index + 1);
            metadata.put("vector_score", result.score());
            ranked.add(new SimilaritySearchResult(
                    index + 1, result.score(), result.text(), Map.copyOf(metadata)
            ));
        }
        return List.copyOf(ranked);
    }

    private MultiDocumentSimilaritySearchResponse response(
            String responseQuestion,
            MultiDocumentSimilaritySearchResponse vectorResponse,
            int topK,
            List<SimilaritySearchResult> results) {
        return new MultiDocumentSimilaritySearchResponse(
                responseQuestion.strip(),
                vectorResponse.documentIds(),
                topK,
                results.size(),
                results
        );
    }

    private static final class Candidate {
        private final String chunkId;
        private String text;
        private final Map<String, Object> metadata;
        private int vectorRank;
        private int keywordRank;
        private double vectorScore;
        private double keywordScore;
        private double rrfScore;

        private Candidate(
                String chunkId,
                String text,
                Map<String, Object> metadata) {
            this.chunkId = chunkId;
            this.text = text;
            this.metadata = new LinkedHashMap<>(metadata);
            this.metadata.put("chunk_id", chunkId);
        }

        static Candidate fromVector(String chunkId, SimilaritySearchResult result) {
            return new Candidate(chunkId, result.text(), result.metadata());
        }

        static Candidate fromKeyword(KeywordSearchResult result) {
            return new Candidate(result.chunkId(), result.text(), result.metadata());
        }

        void addVector(int rank, double score, double contribution) {
            this.vectorRank = rank;
            this.vectorScore = score;
            this.rrfScore += contribution;
        }

        void addKeyword(int rank, double score, double contribution) {
            this.keywordRank = rank;
            this.keywordScore = score;
            this.rrfScore += contribution;
        }

        String chunkId() {
            return this.chunkId;
        }

        String text() {
            return this.text;
        }

        Map<String, Object> metadata() {
            return this.metadata;
        }

        double rrfScore() {
            return this.rrfScore;
        }

        int bestRank() {
            int first = this.vectorRank == 0 ? Integer.MAX_VALUE : this.vectorRank;
            int second = this.keywordRank == 0 ? Integer.MAX_VALUE : this.keywordRank;
            return Math.min(first, second);
        }

        Map<String, Object> enrichedMetadata() {
            Map<String, Object> enriched = new LinkedHashMap<>(this.metadata);
            enriched.put("retrieval_mode", "hybrid");
            enriched.put("rrf_score", this.rrfScore);
            if (this.vectorRank > 0) {
                enriched.put("vector_rank", this.vectorRank);
                enriched.put("vector_score", this.vectorScore);
            }
            if (this.keywordRank > 0) {
                enriched.put("keyword_rank", this.keywordRank);
                enriched.put("keyword_score", this.keywordScore);
            }
            return Map.copyOf(enriched);
        }
    }
}
