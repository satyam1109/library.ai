package com.libraryai.document;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import static org.assertj.core.api.Assertions.assertThat;

class PdfTextCleaningServiceTest {

    private final PdfTextCleaningService cleaningService = new PdfTextCleaningService();

    @Test
    void cleansLayoutWhitespaceAndRemovesRepeatedFooters() {
        List<Document> pages = List.of(
                page("""
                        Java       Fundamentals:                    JVM,        JDK

                             Java source code is usually written in files with the .java
                             extension. The compiler creates bytecode.

                        Java Chunking Test Document     Page 1 of 3
                        """, 1),
                page("""
                        Object-Oriented       Programming

                        Encapsulation controls access to state.

                        Java Chunking Test Document     Page 2 of 3
                        """, 2),
                page("""
                        Java       Collections

                        A Map stores key-value pairs.

                        Java Chunking Test Document     Page 3 of 3
                        """, 3)
        );

        List<Document> cleaned = cleaningService.clean(pages);

        assertThat(cleaned.getFirst().getText()).isEqualTo("""
                Java Fundamentals: JVM, JDK

                Java source code is usually written in files with the .java extension. The compiler creates bytecode.""");
        assertThat(cleaned.getFirst().getText()).doesNotContain("Page 1 of 3");
        assertThat(cleaned.getFirst().getMetadata())
                .containsEntry("text_cleaned", true)
                .containsEntry("removed_margin_line_count", 1)
                .containsEntry("source_file_name", "test.pdf")
                .containsKeys("source_original_character_count", "source_cleaned_character_count")
                .doesNotContainKeys("file_name", "original_character_count", "cleaned_character_count");
    }

    @Test
    void preservesLineBreaksInsideCodeBlocks() {
        Document page = page("""
                Example

                    public interface NotificationSender {
                        void send(String message);
                    }
                """, 1);

        String cleaned = cleaningService.clean(List.of(page)).getFirst().getText();

        assertThat(cleaned).isEqualTo("""
                Example

                public interface NotificationSender {
                    void send(String message);
                }""");
    }

    @Test
    void joinsWordsSplitByEndOfLineHyphenation() {
        Document page = page("""
                The applica-
                tion creates an embedding.
                """, 1);

        String cleaned = cleaningService.clean(List.of(page)).getFirst().getText();

        assertThat(cleaned).isEqualTo("The application creates an embedding.");
    }

    @Test
    void joinsWrappedListContinuationsButKeepsSeparateItems() {
        Document page = page("""
                - First item continues across a random PDF
                  line wrap without changing its meaning.
                - Second item remains separate from the first.
                """, 1);

        String cleaned = cleaningService.clean(List.of(page)).getFirst().getText();

        assertThat(cleaned).isEqualTo("""
                - First item continues across a random PDF line wrap without changing its meaning.
                - Second item remains separate from the first.""");
    }

    @Test
    void preservesUnicodeBulletsAsSeparateListItems() {
        Document page = page("""
                • First item continues across a random PDF
                  line wrap without changing its meaning.
                ● Second item remains separate.
                ⁃ Third item remains separate.
                 Fourth item remains separate.
                """, 1);

        String cleaned = cleaningService.clean(List.of(page)).getFirst().getText();

        assertThat(cleaned).isEqualTo("""
                • First item continues across a random PDF line wrap without changing its meaning.
                ● Second item remains separate.
                ⁃ Third item remains separate.
                 Fourth item remains separate.""");
    }

    @Test
    void doesNotRemoveRepeatedSemanticChildHeadingsNearPageMargins() {
        List<Document> pages = List.of(
                page("""
                        1. First Topic
                        Concept
                        First topic explanation.
                        """, 1),
                page("""
                        2. Second Topic
                        Concept
                        Second topic explanation.
                        """, 2),
                page("""
                        3. Third Topic
                        Concept
                        Third topic explanation.
                        """, 3)
        );

        List<Document> cleaned = cleaningService.clean(pages);

        assertThat(cleaned).allSatisfy(page -> assertThat(page.getText()).contains("Concept"));
    }

    @Test
    void removesStandalonePageNumbersEvenWhenTheyAreNotRepeatedText() {
        List<Document> pages = List.of(
                page("""
                        First page content.

                        1
                        """, 1),
                page("""
                        Second page content.

                        - 2 -
                        """, 2)
        );

        List<Document> cleaned = cleaningService.clean(pages);

        assertThat(cleaned.get(0).getText()).isEqualTo("First page content.");
        assertThat(cleaned.get(1).getText()).isEqualTo("Second page content.");
    }

    @Test
    void createsABoundaryAroundAHeadingWhenPdfBlankLinesAreMissing() {
        Document page = page("""
                Previous paragraph ends here.
                2.1 New Topic
                The new topic starts here and wraps
                onto another visual line.
                """, 1);

        String cleaned = cleaningService.clean(List.of(page)).getFirst().getText();

        assertThat(cleaned).isEqualTo("""
                Previous paragraph ends here.

                2.1 New Topic

                The new topic starts here and wraps onto another visual line.""");
    }

    @Test
    void createsBoundariesAroundKnownChildLabelsWhenBlankLinesAreMissing() {
        Document page = page("""
                1. Dependency Injection
                Concept
                Dependencies are supplied by a container.
                How it works
                The container constructs and connects objects.
                """, 1);

        String cleaned = cleaningService.clean(List.of(page)).getFirst().getText();

        assertThat(cleaned).isEqualTo("""
                1. Dependency Injection

                Concept

                Dependencies are supplied by a container.

                How it works

                The container constructs and connects objects.""");
    }

    @Test
    void doesNotTreatSemicolonEndingProseAsCode() {
        Document page = page("""
                Prefer a complete explanation; do not classify ordinary prose as Java code;
                This sentence should join as normal prose.
                """, 1);

        String cleaned = cleaningService.clean(List.of(page)).getFirst().getText();

        assertThat(cleaned).isEqualTo(
                "Prefer a complete explanation; do not classify ordinary prose as Java code; "
                        + "This sentence should join as normal prose.");
    }

    @Test
    void normalizesPdfExtractionSpacesInsideCodeStringLiterals() {
        Document page = page("""
                public class Message {
                    String value = "keep   these   spaces";
                }
                """, 1);

        String cleaned = cleaningService.clean(List.of(page)).getFirst().getText();

        assertThat(cleaned).contains("\"keep these spaces\"");
    }

    @Test
    void carriesBraceIndentationAcrossBlankSeparatedCodeFragments() {
        Document page = page("""
                public class Counter {
                    private int value;

                    public void increment() {
                        value++;
                    }

                    public int value() {
                        return value;
                    }
                }
                """, 1);

        String cleaned = cleaningService.clean(List.of(page)).getFirst().getText();

        assertThat(cleaned).isEqualTo("""
                public class Counter {
                    private int value;

                    public void increment() {
                        value++;
                    }

                    public int value() {
                        return value;
                    }
                }""");
    }

    private Document page(String text, int pageNumber) {
        return new Document(text, Map.of("page_number", pageNumber, "file_name", "test.pdf"));
    }
}
