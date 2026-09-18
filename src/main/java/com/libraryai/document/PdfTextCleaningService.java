package com.libraryai.document;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

/**
 * Cleans layout artifacts from page-level PDF text before token-based splitting.
 * The rules are intentionally conservative because cleaning must not rewrite the
 * meaning of the source material.
 */
@Service
public class PdfTextCleaningService {

    private static final int MARGIN_CANDIDATE_LINES = 3;
    private static final double REPEATED_MARGIN_RATIO = 0.60;

    private static final Pattern HORIZONTAL_WHITESPACE =
            Pattern.compile("[\\p{Zs}\\t\\x0B\\f]+");
    private static final Pattern PAGE_COUNTER =
            Pattern.compile("(?i)\\bpage\\s+\\d+\\s+(?:of|/)\\s*\\d+\\b");
    private static final Pattern STANDALONE_PAGE_NUMBER = Pattern.compile(
            "(?i)^\\s*(?:page\\s+)?[-–—]?\\s*(?:\\d+|[ivxlcdm]+)(?:\\s*(?:of|/)\\s*\\d+)?\\s*[-–—]?\\s*$");
    private static final Pattern NUMBERED_HEADING =
            Pattern.compile("^(\\d+(?:\\.\\d+)*)[.)]?\\s+(.+)");
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^#{1,6}\\s+.+");
    private static final Pattern COMMON_CHILD_HEADING = Pattern.compile(
            "(?i)^(?:concept|how it works|practical considerations?|examples?|engineering notes?|chunking checkpoints?)$");
    private static final Pattern LIST_LINE =
            Pattern.compile("^(?:[-*•‣◦▪▫●○■□‒–—⁃∙]|\\d+[.)]|[A-Za-z][.)])\\s+.+");
    private static final Pattern CODE_KEYWORD = Pattern.compile(
            "^(?:@\\w+|(?:public|private|protected|static|final|abstract|class|interface|enum|record|void|return|if|else|for|while|try|catch|package|import|throw|throws)\\b.*)");
    private static final Pattern CODE_ASSIGNMENT_OR_CALL = Pattern.compile(
            "^(?:(?:[\\w.$<>?\\[\\],]+\\s+)+\\w+\\s*=.+;|[\\w.$]+\\s*\\([^;]*\\);)$");

    public List<Document> clean(List<Document> pageDocuments) {
        if (pageDocuments == null || pageDocuments.isEmpty()) {
            return List.of();
        }

        List<List<String>> normalizedPages = pageDocuments.stream()
                .map(Document::getText)
                .map(this::normalizeLines)
                .toList();

        Set<String> repeatedMarginLines = findRepeatedMarginLines(normalizedPages);
        List<Document> cleanedDocuments = new ArrayList<>(pageDocuments.size());

        for (int index = 0; index < pageDocuments.size(); index++) {
            Document original = pageDocuments.get(index);
            PageCleaningResult cleanedPage = cleanPage(normalizedPages.get(index), repeatedMarginLines);
            Map<String, Object> metadata = normalizeMetadata(original.getMetadata());
            metadata.put(LibraryDocumentMetadata.TEXT_CLEANED, true);
            metadata.put(LibraryDocumentMetadata.SOURCE_ORIGINAL_CHARACTER_COUNT, safeText(original).length());
            metadata.put(LibraryDocumentMetadata.SOURCE_CLEANED_CHARACTER_COUNT, cleanedPage.text().length());
            metadata.put(LibraryDocumentMetadata.REMOVED_MARGIN_LINE_COUNT, cleanedPage.removedMarginLineCount());

            cleanedDocuments.add(new Document(original.getId(), cleanedPage.text(), metadata));
        }

        return List.copyOf(cleanedDocuments);
    }

    private List<String> normalizeLines(String text) {
        String normalizedText = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                .replace('\u00A0', ' ')
                .replace("\r\n", "\n")
                .replace('\r', '\n');

        return normalizedText.lines()
                .map(this::normalizeLine)
                .toList();
    }

    private String normalizeLine(String line) {
        String withoutTrailingWhitespace = line == null ? "" : line.stripTrailing();
        int sourceIndex = 0;
        int indentation = 0;
        while (sourceIndex < withoutTrailingWhitespace.length()
                && Character.isWhitespace(withoutTrailingWhitespace.charAt(sourceIndex))) {
            indentation += withoutTrailingWhitespace.charAt(sourceIndex) == '\t' ? 4 : 1;
            sourceIndex++;
        }

        String content = HORIZONTAL_WHITESPACE
                .matcher(withoutTrailingWhitespace.substring(sourceIndex))
                .replaceAll(" ")
                .stripTrailing();
        if (content.isBlank()) {
            return "";
        }

        // Retain bounded indentation so code structure survives extraction. Prose
        // joins call strip(), so incidental PDF positioning spaces do not leak in.
        return " ".repeat(Math.min(indentation, 40)) + content;
    }

    private Set<String> findRepeatedMarginLines(List<List<String>> pages) {
        if (pages.size() < 2) {
            return Set.of();
        }

        Map<String, Integer> pageOccurrences = new HashMap<>();

        for (List<String> page : pages) {
            Set<String> candidatesOnThisPage = new HashSet<>();
            for (int lineIndex : marginLineIndexes(page)) {
                String canonicalLine = canonicalMarginLine(page.get(lineIndex));
                if (!canonicalLine.isBlank()) {
                    candidatesOnThisPage.add(canonicalLine);
                }
            }
            candidatesOnThisPage.forEach(line -> pageOccurrences.merge(line, 1, Integer::sum));
        }

        int requiredOccurrences = Math.max(
                2,
                (int) Math.ceil(pages.size() * REPEATED_MARGIN_RATIO)
        );

        Set<String> repeatedLines = new HashSet<>();
        pageOccurrences.forEach((line, count) -> {
            if (count >= requiredOccurrences) {
                repeatedLines.add(line);
            }
        });
        return Set.copyOf(repeatedLines);
    }

    private PageCleaningResult cleanPage(List<String> lines, Set<String> repeatedMarginLines) {
        Set<Integer> marginIndexes = marginLineIndexes(lines);
        List<String> retainedLines = new ArrayList<>(lines.size());
        int removedMarginLines = 0;

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            boolean repeatedMargin = marginIndexes.contains(index)
                    && !isExplicitHeadingLine(line)
                    && (repeatedMarginLines.contains(canonicalMarginLine(line))
                    || STANDALONE_PAGE_NUMBER.matcher(line).matches());

            if (repeatedMargin) {
                removedMarginLines++;
                retainedLines.add("");
            } else {
                retainedLines.add(line);
            }
        }

        String cleanedText = cleanBlocks(retainedLines);
        return new PageCleaningResult(cleanedText, removedMarginLines);
    }

    private Set<Integer> marginLineIndexes(List<String> lines) {
        List<Integer> nonBlankIndexes = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            if (!lines.get(index).isBlank()) {
                nonBlankIndexes.add(index);
            }
        }

        Set<Integer> marginIndexes = new HashSet<>();
        int candidateCount = Math.min(MARGIN_CANDIDATE_LINES, nonBlankIndexes.size());
        marginIndexes.addAll(nonBlankIndexes.subList(0, candidateCount));
        marginIndexes.addAll(nonBlankIndexes.subList(nonBlankIndexes.size() - candidateCount, nonBlankIndexes.size()));
        return marginIndexes;
    }

    private String canonicalMarginLine(String line) {
        String withoutPageNumber = PAGE_COUNTER.matcher(line).replaceAll("page # of #");
        return withoutPageNumber.toLowerCase(Locale.ROOT).strip();
    }

    private String cleanBlocks(List<String> lines) {
        List<String> cleanedBlocks = new ArrayList<>();
        List<String> currentBlock = new ArrayList<>();
        int codeNestingDepth = 0;

        for (String line : lines) {
            if (line.isBlank()) {
                codeNestingDepth = addCleanedBlock(cleanedBlocks, currentBlock, codeNestingDepth);
                currentBlock.clear();
            } else if (isExplicitHeadingLine(line)) {
                // Some extractors omit blank lines around visible headings. Force a
                // semantic boundary so the chunker never absorbs the heading into
                // the paragraph above it.
                codeNestingDepth = addCleanedBlock(cleanedBlocks, currentBlock, codeNestingDepth);
                currentBlock.clear();
                cleanedBlocks.add(line.strip());
                codeNestingDepth = 0;
            } else {
                currentBlock.add(line);
            }
        }
        addCleanedBlock(cleanedBlocks, currentBlock, codeNestingDepth);

        return String.join("\n\n", cleanedBlocks).strip();
    }

    private int addCleanedBlock(
            List<String> cleanedBlocks,
            List<String> blockLines,
            int codeNestingDepth) {
        if (blockLines.isEmpty()) {
            return codeNestingDepth;
        }

        String cleanedBlock;
        int nextCodeNestingDepth = 0;
        if (isCodeBlock(blockLines)) {
            // Code is line-oriented. Flattening it would destroy statement structure.
            cleanedBlock = normalizeCodeIndentation(blockLines, codeNestingDepth);
            nextCodeNestingDepth = Math.max(
                    0,
                    codeNestingDepth + braceDelta(cleanedBlock)
            );
        } else if (blockLines.stream().anyMatch(this::isListLine)) {
            cleanedBlock = joinWrappedListItems(blockLines);
        } else {
            cleanedBlock = joinWrappedProse(blockLines);
        }

        if (!cleanedBlock.isBlank()) {
            cleanedBlocks.add(cleanedBlock);
        }
        return nextCodeNestingDepth;
    }

    private boolean isCodeBlock(List<String> lines) {
        long codeSignals = lines.stream()
                .map(String::strip)
                .filter(this::looksLikeCodeLine)
                .count();
        if (lines.size() == 1) {
            return codeSignals == 1;
        }
        return codeSignals >= 2 || codeSignals * 2 >= lines.size();
    }

    private boolean looksLikeCodeLine(String line) {
        if (line.equals("{") || line.equals("}") || line.startsWith("@")) {
            return true;
        }
        boolean hasJavaSyntax = line.indexOf(';') >= 0
                || line.indexOf('{') >= 0
                || line.indexOf('}') >= 0
                || line.indexOf('(') >= 0;
        return CODE_KEYWORD.matcher(line).matches() && hasJavaSyntax
                || CODE_ASSIGNMENT_OR_CALL.matcher(line).matches()
                || line.contains("->") && (line.contains("=") || line.endsWith(";"));
    }

    private String normalizeCodeIndentation(List<String> lines, int codeNestingDepth) {
        int commonIndentation = lines.stream()
                .filter(line -> !line.isBlank())
                .mapToInt(this::leadingSpaces)
                .min()
                .orElse(0);
        boolean braceStructured = lines.stream().anyMatch(line -> line.contains("{") || line.contains("}"));
        if (braceStructured) {
            return normalizeBraceStructuredCode(lines, codeNestingDepth);
        }

        int indentationStep = lines.stream()
                .filter(line -> !line.isBlank())
                .mapToInt(line -> leadingSpaces(line) - commonIndentation)
                .filter(indentation -> indentation > 0)
                .reduce(0, this::greatestCommonDivisor);
        if (indentationStep == 0) {
            indentationStep = 4;
        }

        int finalIndentationStep = indentationStep;
        String baseIndentation = " ".repeat(codeNestingDepth * 4);
        return lines.stream()
                .map(line -> {
                    String content = line.substring(Math.min(commonIndentation, line.length())).stripLeading();
                    int relativeIndentation = Math.max(0, leadingSpaces(line) - commonIndentation);
                    int normalizedIndentation = Math.round((float) relativeIndentation / finalIndentationStep) * 4;
                    return baseIndentation + " ".repeat(normalizedIndentation) + content;
                })
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private String normalizeBraceStructuredCode(List<String> lines, int startingBraceDepth) {
        List<String> normalizedLines = new ArrayList<>(lines.size());
        int braceDepth = startingBraceDepth;
        int parenthesisDepth = 0;

        for (String sourceLine : lines) {
            String content = sourceLine.strip();
            int leadingClosures = 0;
            while (leadingClosures < content.length() && content.charAt(leadingClosures) == '}') {
                leadingClosures++;
            }

            int lineDepth = Math.max(0, braceDepth - leadingClosures);
            int continuationIndent = parenthesisDepth > 0 ? 4 : 0;
            normalizedLines.add(" ".repeat(lineDepth * 4 + continuationIndent) + content);

            braceDepth = Math.max(0, braceDepth + braceDelta(content));
            parenthesisDepth = Math.max(
                    0,
                    parenthesisDepth + countOutsideQuotes(content, '(') - countOutsideQuotes(content, ')')
            );
        }
        return String.join("\n", normalizedLines);
    }

    private int braceDelta(String code) {
        return countOutsideQuotes(code, '{') - countOutsideQuotes(code, '}');
    }

    private int countOutsideQuotes(String text, char target) {
        int count = 0;
        boolean insideSingleQuote = false;
        boolean insideDoubleQuote = false;
        boolean escaped = false;

        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == target && !insideSingleQuote && !insideDoubleQuote) {
                count++;
            }
            if (character == '\'' && !insideDoubleQuote && !escaped) {
                insideSingleQuote = !insideSingleQuote;
            } else if (character == '"' && !insideSingleQuote && !escaped) {
                insideDoubleQuote = !insideDoubleQuote;
            }
            escaped = character == '\\' && !escaped;
            if (character != '\\') {
                escaped = false;
            }
        }
        return count;
    }

    private int greatestCommonDivisor(int left, int right) {
        int first = Math.abs(left);
        int second = Math.abs(right);
        while (second != 0) {
            int remainder = first % second;
            first = second;
            second = remainder;
        }
        return first;
    }

    private int leadingSpaces(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private boolean isListLine(String line) {
        return LIST_LINE.matcher(line.strip()).matches();
    }

    private boolean isExplicitHeadingLine(String line) {
        String candidate = line.strip();
        if (MARKDOWN_HEADING.matcher(candidate).matches()) {
            return true;
        }
        if (COMMON_CHILD_HEADING.matcher(candidate).matches()) {
            return true;
        }

        java.util.regex.Matcher numbered = NUMBERED_HEADING.matcher(candidate);
        if (!numbered.matches()) {
            return false;
        }

        // Decimal hierarchy such as 2.3 is unambiguously a heading. A single
        // number such as "1. ..." can also be a numbered list, so require a
        // short title-like label before forcing a paragraph boundary.
        if (numbered.group(1).contains(".")) {
            return true;
        }
        return looksLikeTitle(numbered.group(2));
    }

    private boolean looksLikeTitle(String text) {
        String candidate = text.strip();
        if (candidate.isBlank()
                || candidate.split("\\s+").length > 14
                || ".?!;:{}".indexOf(candidate.charAt(candidate.length() - 1)) >= 0) {
            return false;
        }

        String[] words = candidate.split("\\s+");
        long meaningful = java.util.Arrays.stream(words)
                .filter(word -> word.chars().anyMatch(Character::isLetter))
                .count();
        long titleCase = java.util.Arrays.stream(words)
                .filter(word -> word.codePoints()
                        .filter(Character::isLetter)
                        .findFirst()
                        .stream()
                        .anyMatch(Character::isUpperCase))
                .count();
        return meaningful > 0 && titleCase * 2 >= meaningful;
    }

    private String joinWrappedListItems(List<String> lines) {
        List<String> items = new ArrayList<>();
        List<String> currentItemLines = new ArrayList<>();

        for (String line : lines) {
            String normalizedLine = line.strip();
            if (isListLine(normalizedLine) && !currentItemLines.isEmpty()) {
                items.add(joinWrappedProse(currentItemLines));
                currentItemLines.clear();
            }
            currentItemLines.add(normalizedLine);
        }

        if (!currentItemLines.isEmpty()) {
            items.add(joinWrappedProse(currentItemLines));
        }
        return String.join("\n", items);
    }

    private String joinWrappedProse(List<String> lines) {
        StringBuilder joined = new StringBuilder();

        for (String line : lines) {
            String normalizedLine = line.strip();
            if (joined.isEmpty()) {
                joined.append(normalizedLine);
                continue;
            }

            boolean wrappedHyphenatedWord = joined.charAt(joined.length() - 1) == '-'
                    && !normalizedLine.isEmpty()
                    && Character.isLowerCase(normalizedLine.codePointAt(0));

            if (wrappedHyphenatedWord) {
                joined.deleteCharAt(joined.length() - 1);
            } else {
                joined.append(' ');
            }
            joined.append(normalizedLine);
        }

        return joined.toString();
    }

    private String safeText(Document document) {
        return document.getText() == null ? "" : document.getText();
    }

    private Map<String, Object> normalizeMetadata(Map<String, Object> originalMetadata) {
        Map<String, Object> metadata = new HashMap<>(originalMetadata);
        Object fileName = metadata.remove("file_name");
        if (fileName != null) {
            metadata.put(LibraryDocumentMetadata.SOURCE_FILE_NAME, fileName);
        }
        return metadata;
    }

    private record PageCleaningResult(String text, int removedMarginLineCount) {
    }
}
