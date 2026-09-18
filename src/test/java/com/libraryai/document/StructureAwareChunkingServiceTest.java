package com.libraryai.document;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import static org.assertj.core.api.Assertions.assertThat;

class StructureAwareChunkingServiceTest {

    private final StructureAwareChunkingService chunkingService = new StructureAwareChunkingService();

    @Test
    void keepsAFittingCodeBlockTogether() {
        Document page = page("""
                2.2 Interface-Based Design Example

                public interface NotificationSender {
                void send(String message);
                }

                public class EmailSender implements NotificationSender {
                @Override
                public void send(String message) {
                System.out.println("EMAIL: " + message);
                }
                }
                """);

        List<Document> chunks = chunkingService.chunk(List.of(page));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().getText())
                .contains("public interface NotificationSender")
                .contains("System.out.println(\"EMAIL: \" + message);");
        assertThat(chunks.getFirst().getMetadata()).containsEntry("contains_code", true);
        assertThat(chunks.getFirst().getMetadata())
                .containsEntry("page_chunk_index", 0)
                .containsEntry("page_chunk_count", 1)
                .containsKeys("source_document_id", "estimated_token_count", "chunk_character_count")
                .doesNotContainKeys("parent_document_id", "total_chunks", "global_chunk_index");
    }

    @Test
    void splitsOversizedCodeOnlyBetweenCompleteLines() {
        String largeCodeBlock = IntStream.range(0, 350)
                .mapToObj(index -> "System.out.println(\"line " + index + "\");")
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow();

        List<Document> chunks = chunkingService.chunk(List.of(page(largeCodeBlock)));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.getText()).doesNotEndWith("System.out.");
            assertThat(chunk.getText().lines())
                    .allSatisfy(line -> assertThat(line).endsWith(");"));
            assertThat(chunk.getMetadata()).containsEntry("contains_code", true);
        });
    }

    @Test
    void keepsHeadingWithItsFollowingParagraph() {
        Document page = page("""
                1. Java Execution Model

                Java source code is compiled into bytecode that runs on the JVM.
                """);

        List<Document> chunks = chunkingService.chunk(List.of(page));

        assertThat(chunks).singleElement().satisfies(chunk ->
                assertThat(chunk.getText()).isEqualTo("""
                        1. Java Execution Model

                        Java source code is compiled into bytecode that runs on the JVM."""));
    }

    @Test
    void overlapsOnlyWhenTokenLimitSplitsTheSameTopic() {
        String firstParagraph = "Dependency injection reduces coupling and improves testability. ".repeat(35);
        String continuation = "The same topic continues with constructor injection examples. ".repeat(20);
        Document page = page("""
                1. Dependency Injection

                %s

                %s
                """.formatted(firstParagraph, continuation));

        List<Document> chunks = chunkingService.chunk(List.of(page));

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        assertThat(chunks.get(1).getMetadata())
                .containsEntry("starts_new_topic", false)
                .containsEntry("structural_boundary", "same_topic_token_limit")
                .containsEntry("overlap_applied", true);
        assertThat((Integer) chunks.get(1).getMetadata().get("overlap_token_count"))
                .isBetween(1, StructureAwareChunkingService.MAX_OVERLAP_TOKENS);
    }

    @Test
    void doesNotOverlapAcrossAHeadingBoundary() {
        String firstTopic = "The JVM executes portable bytecode and manages runtime memory. ".repeat(25);
        String secondTopic = "Collections organize objects for different access patterns. ".repeat(20);
        Document page = page("""
                1. JVM Fundamentals

                %s

                2. Java Collections

                %s
                """.formatted(firstTopic, secondTopic));

        List<Document> chunks = chunkingService.chunk(List.of(page));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(1).getText()).startsWith("2. Java Collections");
        assertThat(chunks.get(1).getMetadata())
                .containsEntry("starts_new_topic", true)
                .containsEntry("overlap_applied", false)
                .containsEntry("overlap_token_count", 0);
    }

    @Test
    void allowsTheSameSectionToContinueAcrossPageBoundaries() {
        Document firstPage = page("""
                1. Dependency Injection

                Constructor injection makes required dependencies explicit.
                """, 1);
        Document secondPage = page("""
                It also makes services easier to instantiate in focused unit tests.
                """, 2);

        List<Document> chunks = chunkingService.chunk(List.of(firstPage, secondPage));

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.getText())
                    .contains("Constructor injection")
                    .contains("focused unit tests");
            assertThat(chunk.getMetadata())
                    .containsEntry("section_title", "1. Dependency Injection")
                    .containsEntry("spans_pages", true)
                    .containsEntry("start_page_number", 1)
                    .containsEntry("end_page_number", 2)
                    .containsEntry("page_numbers", List.of(1, 2));
            assertThat((List<?>) chunk.getMetadata().get("source_document_ids")).hasSize(2);
        });
    }

    @Test
    void startsANewChunkWheneverANewMajorHeadingBegins() {
        Document page = page("""
                1. First Topic

                A short but useful explanation belongs to the first topic.

                2. Second Topic

                Another short explanation belongs to the second topic.
                """);

        List<Document> chunks = chunkingService.chunk(List.of(page));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).getText()).startsWith("1. First Topic");
        assertThat(chunks.get(1).getText()).startsWith("2. Second Topic");
        assertThat(chunks.get(1).getMetadata())
                .containsEntry("section_title", "2. Second Topic")
                .containsEntry("structural_boundary", "new_section")
                .containsEntry("overlap_applied", false);
    }

    @Test
    void groupsSubheadingsUnderTheirMajorSectionUntilTheTokenTarget() {
        Document page = page("""
                1. Dependency Injection

                The major section introduces dependency injection clearly.

                1.1 Concept

                Dependencies are supplied from outside the consuming object.

                1.2 How It Works

                A container constructs objects and connects their collaborators.

                1.3 Practical Considerations

                Constructor injection makes requirements explicit and testable.

                1.4 Example

                A service receives a repository through its constructor.
                """);

        List<Document> chunks = chunkingService.chunk(List.of(page));

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.getText())
                    .contains("1.1 Concept", "1.2 How It Works", "1.3 Practical Considerations", "1.4 Example");
            assertThat(chunk.getMetadata())
                    .containsEntry("section_title", "1. Dependency Injection")
                    .containsEntry("subsection_titles", List.of(
                            "1.1 Concept",
                            "1.2 How It Works",
                            "1.3 Practical Considerations",
                            "1.4 Example"
                    ))
                    .containsEntry("max_heading_level", 2);
        });
    }

    @Test
    void splitsAtASubheadingBoundaryWithSmallOverlapInsideTheSameParent() {
        String introduction = "A concise sentence preserves retrieval context. ".repeat(28);
        String practicalAdvice = "Prefer constructor injection for mandatory collaborators. ".repeat(15);
        Document page = page("""
                1. Dependency Injection

                %s

                1.1 Practical Considerations

                %s
                """.formatted(introduction, practicalAdvice));

        List<Document> chunks = chunkingService.chunk(List.of(page));
        Document subsectionChunk = chunks.stream()
                .filter(chunk -> "same_section_structural_split".equals(
                        chunk.getMetadata().get("structural_boundary")))
                .findFirst()
                .orElseThrow();

        assertThat(subsectionChunk.getText()).contains("1.1 Practical Considerations");
        assertThat(subsectionChunk.getMetadata())
                .containsEntry("section_title", "1. Dependency Injection")
                .containsEntry("structural_boundary", "same_section_structural_split")
                .containsEntry("overlap_applied", true);
        assertThat((Integer) subsectionChunk.getMetadata().get("overlap_token_count"))
                .isBetween(1, StructureAwareChunkingService.MAX_OVERLAP_TOKENS);
    }

    @Test
    void keepsCommonUnnumberedLabelsAsChildrenOfTheNumberedParent() {
        Document page = page("""
                12. Dependency Injection

                Concept

                Dependencies are supplied from outside the consuming object.

                How it works

                The container constructs objects and connects collaborators.

                Practical considerations

                Constructor injection makes requirements explicit.

                Example

                A service receives its repository through the constructor.

                Engineering note

                Keep the parent heading available during retrieval.

                Chunking checkpoints

                • Preserve the numbered parent heading.
                • Keep child labels in hierarchy metadata.
                """);

        List<Document> chunks = chunkingService.chunk(List.of(page));

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.getMetadata())
                    .containsEntry("section_title", "12. Dependency Injection")
                    .containsEntry("subsection_titles", List.of(
                            "Concept",
                            "How it works",
                            "Practical considerations",
                            "Example",
                            "Engineering note",
                            "Chunking checkpoints"
                    ))
                    .containsEntry("contains_list", true);
            assertThat(chunk.getMetadata().get("starts_new_topic")).isEqualTo(true);
        });
    }

    @Test
    void doesNotTreatSemicolonEndingProseAsCode() {
        Document page = page("""
                1. Writing Style

                Engineering note

                Prefer a complete explanation; do not classify ordinary prose as Java code;
                """);

        List<Document> chunks = chunkingService.chunk(List.of(page));

        assertThat(chunks).singleElement().satisfies(chunk ->
                assertThat(chunk.getMetadata()).containsEntry("contains_code", false));
    }

    @Test
    void recognizesUnicodeBulletLists() {
        Document page = page("""
                1. Collection Choices

                • Use List when order matters.
                ● Use Set when uniqueness matters.
                ⁃ Use Map for key-value lookup.
                 Use Queue for ordered processing.
                """);

        List<Document> chunks = chunkingService.chunk(List.of(page));

        assertThat(chunks).singleElement().satisfies(chunk ->
                assertThat(chunk.getMetadata()).containsEntry("contains_list", true));
    }

    @Test
    void mergesATinySameSectionTailWhenTheHardLimitAllowsIt() {
        String mainContent = "A complete sentence explains one useful retrieval concept. ".repeat(58);
        String tinyTail = "This final note is short.";
        Document page = page("""
                1. Retrieval Design

                %s %s
                """.formatted(mainContent, tinyTail));

        List<Document> chunks = chunkingService.chunk(List.of(page));

        assertThat(chunks).allSatisfy(chunk ->
                assertThat((Integer) chunk.getMetadata().get("content_token_count"))
                        .isGreaterThanOrEqualTo(StructureAwareChunkingService.MIN_USEFUL_CHUNK_TOKENS));
        assertThat(chunks.getLast().getText()).contains(tinyTail);
    }

    @Test
    void preservesCompleteListItemsWhenAnOversizedListMustSplit() {
        String list = IntStream.range(0, 90)
                .mapToObj(index -> "- Item " + index + " explains one complete retrieval rule.")
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow();

        List<Document> chunks = chunkingService.chunk(List.of(page(list)));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.getMetadata()).containsEntry("contains_list", true);
            assertThat(chunk.getText().lines())
                    .allSatisfy(line -> assertThat(line).startsWith("- Item"));
        });
    }

    @Test
    void keepsAMultilineMethodSignatureTogetherWhenLargeCodeMustSplit() {
        String statements = IntStream.range(0, 180)
                .mapToObj(index -> "    System.out.println(\"line " + index + "\");")
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow();
        String code = """
                public class Processor {
                    public void process(
                            String firstArgument,
                            String secondArgument) {
                %s
                    }
                }
                """.formatted(statements);

        List<Document> chunks = chunkingService.chunk(List.of(page(code)));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.getFirst().getText())
                .contains("public void process(")
                .contains("String firstArgument,")
                .contains("String secondArgument) {");
        assertThat(chunks.stream().skip(1).map(Document::getText))
                .noneMatch(text -> text.startsWith("String firstArgument")
                        || text.startsWith("String secondArgument"));
    }

    @Test
    void splitsLongProseOnlyBetweenCompleteSentences() {
        String prose = IntStream.range(0, 100)
                .mapToObj(index -> "Sentence " + index + " explains a complete retrieval concept.")
                .reduce((left, right) -> left + " " + right)
                .orElseThrow();

        List<Document> chunks = chunkingService.chunk(List.of(page(prose)));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.getText()).endsWith(".");
            assertThat((Integer) chunk.getMetadata().get("estimated_token_count"))
                    .isLessThanOrEqualTo(StructureAwareChunkingService.HARD_MAX_CHUNK_TOKENS);
        });
    }

    private Document page(String text) {
        return page(text, 1);
    }

    private Document page(String text, int pageNumber) {
        return new Document(text, Map.of(
                "page_number", pageNumber,
                "source_file_name", "test.pdf",
                "text_cleaned", true
        ));
    }
}
