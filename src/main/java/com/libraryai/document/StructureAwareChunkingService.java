package com.libraryai.document;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.document.Document;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Service;

/**
 * Creates RAG-ready chunks from an ordered stream of cleaned PDF pages.
 * Structural blocks are kept intact whenever possible; token counts are a size
 * constraint and become a fallback split point only for oversized structures.
 */
@Service
public class StructureAwareChunkingService {

    static final int TARGET_CHUNK_TOKENS = 250;
    static final int HARD_MAX_CHUNK_TOKENS = 300;
    static final int MIN_PREFERRED_CHUNK_TOKENS = 180;
    static final int MIN_USEFUL_CHUNK_TOKENS = 80;
    static final int MAX_OVERLAP_TOKENS = 24;
    static final int MAX_CHUNKS = 1000;

    private static final Pattern NUMBERED_HEADING =
            Pattern.compile("^(\\d+(?:\\.\\d+)*)[.)]?\\s+(.+)");
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+)");
    private static final Pattern COMMON_CHILD_HEADING = Pattern.compile(
            "(?i)^(?:concept|how it works|practical considerations?|examples?|engineering notes?|chunking checkpoints?)$");
    private static final Pattern LIST_ITEM =
            Pattern.compile("^(?:[-*•‣◦▪▫●○■□‒–—⁃∙]|\\d+[.)]|[A-Za-z][.)])\\s+.+");
    private static final Pattern CODE_KEYWORD = Pattern.compile(
            "^(?:@\\w+|(?:public|private|protected|static|final|abstract|sealed|class|interface|enum|record|void|return|if|else|for|while|switch|case|try|catch|finally|package|import|throw|throws)\\b.*)");
    private static final Pattern CODE_ASSIGNMENT_OR_CALL = Pattern.compile(
            "^(?:(?:[\\w.$<>?\\[\\],]+\\s+)+\\w+\\s*=.+;|[\\w.$]+\\s*\\([^;]*\\);)$");

    private static final TokenCountEstimator TOKEN_ESTIMATOR = new JTokkitTokenCountEstimator();

    /** Last-resort splitter for one sentence or source line over the hard limit. */
    private static final TokenTextSplitter HARD_FALLBACK_SPLITTER = TokenTextSplitter.builder()
            .withChunkSize(TARGET_CHUNK_TOKENS)
            .withMinChunkSizeChars(100)
            .withMinChunkLengthToEmbed(20)
            .withMaxNumChunks(MAX_CHUNKS)
            .withKeepSeparator(true)
            .withPunctuationMarks(List.of(';', '}', '\n'))
            .build();

    public List<Document> chunk(List<Document> cleanedPageDocuments) {
        if (cleanedPageDocuments == null || cleanedPageDocuments.isEmpty()) {
            return List.of();
        }

        List<TextUnit> units = buildUnits(cleanedPageDocuments);
        List<ChunkDraft> packed = packUnits(units);
        List<ChunkDraft> balanced = rebalanceUndersizedChunks(packed);
        List<ChunkDraft> overlapped = applyConditionalOverlap(balanced);

        if (overlapped.size() > MAX_CHUNKS) {
            throw new IllegalArgumentException("PDF exceeds the maximum of " + MAX_CHUNKS + " chunks");
        }
        return toDocuments(overlapped);
    }

    private List<TextUnit> buildUnits(List<Document> pages) {
        List<TextUnit> units = new ArrayList<>();
        String currentSection = null;
        String currentSubsection = null;

        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            Document page = pages.get(pageIndex);
            SourceRef source = new SourceRef(
                    pageNumber(page, pageIndex + 1),
                    page.getId(),
                    Map.copyOf(page.getMetadata())
            );
            List<TextBlock> blocks = parseBlocks(page.getText(), source);
            int blockIndex = 0;

            while (blockIndex < blocks.size()) {
                TextBlock block = blocks.get(blockIndex);
                if (block.type() != BlockType.HEADING) {
                    units.addAll(expandBlock(block, currentSection, currentSubsection));
                    blockIndex++;
                    continue;
                }

                List<TextBlock> headingChain = new ArrayList<>();
                while (blockIndex < blocks.size()
                        && blocks.get(blockIndex).type() == BlockType.HEADING) {
                    headingChain.add(blocks.get(blockIndex));
                    blockIndex++;
                }

                boolean startsNewSection = false;
                for (TextBlock heading : headingChain) {
                    if (heading.headingLevel() == 1 || currentSection == null) {
                        currentSection = heading.text();
                        currentSubsection = null;
                        startsNewSection = true;
                    } else {
                        currentSubsection = heading.text();
                    }
                }
                boolean startsSubsection = !startsNewSection;
                int headingLevel = headingChain.getLast().headingLevel();
                String headings = headingChain.stream()
                        .map(TextBlock::text)
                        .reduce((left, right) -> left + "\n\n" + right)
                        .orElseThrow();

                if (blockIndex >= blocks.size()) {
                    units.add(headingUnit(
                            headings, currentSection, currentSubsection, headingLevel,
                            startsNewSection, startsSubsection, source
                    ));
                    continue;
                }

                TextBlock contentBlock = blocks.get(blockIndex++);
                List<TextUnit> contentUnits = expandBlock(
                        contentBlock, currentSection, currentSubsection
                );
                if (contentUnits.isEmpty()) {
                    units.add(headingUnit(
                            headings, currentSection, currentSubsection, headingLevel,
                            startsNewSection, startsSubsection, source
                    ));
                    continue;
                }

                TextUnit firstContent = contentUnits.getFirst();
                units.add(new TextUnit(
                        headings + "\n\n" + firstContent.text(),
                        firstContent.type(),
                        true,
                        startsNewSection,
                        startsSubsection,
                        currentSection,
                        currentSubsection,
                        headingLevel,
                        firstContent.source(),
                        "\n\n"
                ));
                units.addAll(contentUnits.subList(1, contentUnits.size()));
            }
        }

        return units;
    }

    private TextUnit headingUnit(
            String headings,
            String sectionTitle,
            String subsectionTitle,
            int headingLevel,
            boolean startsNewSection,
            boolean startsSubsection,
            SourceRef source) {
        return new TextUnit(
                headings, BlockType.HEADING, true,
                startsNewSection, startsSubsection,
                sectionTitle, subsectionTitle, headingLevel,
                source, "\n\n"
        );
    }

    private List<TextBlock> parseBlocks(String text, SourceRef source) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<TextBlock> blocks = new ArrayList<>();
        for (String rawBlock : text.split("(?:\\R\\s*){2,}")) {
            String block = rawBlock.strip();
            if (!block.isBlank()) {
                BlockType type = classify(block);
                blocks.add(new TextBlock(
                        block,
                        type,
                        type == BlockType.HEADING ? headingLevel(block) : 0,
                        source
                ));
            }
        }
        return blocks;
    }

    private List<TextUnit> expandBlock(
            TextBlock block,
            String sectionTitle,
            String subsectionTitle) {
        List<String> parts = switch (block.type()) {
            case PROSE -> splitProse(block.text());
            case LIST -> splitList(block.text());
            case CODE -> splitCode(block.text());
            case HEADING -> List.of(block.text());
        };

        List<TextUnit> units = new ArrayList<>(parts.size());
        for (int index = 0; index < parts.size(); index++) {
            units.add(new TextUnit(
                    parts.get(index),
                    block.type(),
                    block.type() == BlockType.HEADING,
                    block.type() == BlockType.HEADING,
                    false,
                    sectionTitle,
                    subsectionTitle,
                    block.headingLevel(),
                    block.source(),
                    index == 0 ? "\n\n" : separatorFor(block.type())
            ));
        }
        return units;
    }

    private List<String> splitProse(String prose) {
        if (estimate(prose) <= TARGET_CHUNK_TOKENS) {
            return List.of(prose);
        }

        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String sentence : sentences(prose)) {
            if (estimate(sentence) > HARD_MAX_CHUNK_TOKENS) {
                addText(parts, current);
                current.setLength(0);
                parts.addAll(hardFallback(sentence));
                continue;
            }

            String candidate = current.isEmpty() ? sentence : current + " " + sentence;
            if (!current.isEmpty() && estimate(candidate) > TARGET_CHUNK_TOKENS) {
                addText(parts, current);
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(sentence);
        }

        addText(parts, current);
        return parts;
    }

    private List<String> splitList(String listBlock) {
        if (estimate(listBlock) <= HARD_MAX_CHUNK_TOKENS) {
            return List.of(listBlock);
        }

        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String item : listBlock.lines().map(String::strip).filter(line -> !line.isBlank()).toList()) {
            if (estimate(item) > HARD_MAX_CHUNK_TOKENS) {
                addText(parts, current);
                current.setLength(0);
                parts.addAll(splitProse(item));
                continue;
            }

            String candidate = current.isEmpty() ? item : current + "\n" + item;
            if (!current.isEmpty() && estimate(candidate) > TARGET_CHUNK_TOKENS) {
                addText(parts, current);
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(item);
        }

        addText(parts, current);
        return parts;
    }

    private List<String> splitCode(String codeBlock) {
        if (estimate(codeBlock) <= HARD_MAX_CHUNK_TOKENS) {
            return List.of(codeBlock);
        }

        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String logicalUnit : codeLogicalUnits(codeBlock)) {
            if (estimate(logicalUnit) > HARD_MAX_CHUNK_TOKENS) {
                addText(parts, current);
                current.setLength(0);
                parts.addAll(splitOversizedCodeUnit(logicalUnit));
                continue;
            }

            String candidate = current.isEmpty() ? logicalUnit : current + "\n" + logicalUnit;
            if (!current.isEmpty() && estimate(candidate) > TARGET_CHUNK_TOKENS) {
                addText(parts, current);
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(logicalUnit);
        }

        addText(parts, current);
        return parts;
    }

    /**
     * Finds safe boundaries after fields, declarations, and complete methods.
     * Parenthesis depth prevents a multiline method signature from being split.
     */
    private List<String> codeLogicalUnits(String codeBlock) {
        List<String> units = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int braceDepth = 0;
        int parenthesisDepth = 0;

        for (String line : codeBlock.lines().toList()) {
            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(line);
            braceDepth += count(line, '{') - count(line, '}');
            parenthesisDepth += count(line, '(') - count(line, ')');

            String trimmed = line.strip();
            boolean completeDeclaration = trimmed.endsWith(";") && braceDepth <= 1;
            boolean completeMethodOrType = trimmed.endsWith("}") && braceDepth <= 1;
            if (parenthesisDepth <= 0 && (completeDeclaration || completeMethodOrType)) {
                addText(units, current);
                current.setLength(0);
            }
        }

        addText(units, current);
        return units;
    }

    private List<String> splitOversizedCodeUnit(String codeUnit) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int parenthesisDepth = 0;

        for (String line : codeUnit.lines().toList()) {
            if (estimate(line) > HARD_MAX_CHUNK_TOKENS) {
                addText(parts, current);
                current.setLength(0);
                parts.addAll(hardFallback(line));
                continue;
            }

            String candidate = current.isEmpty() ? line : current + "\n" + line;
            if (!current.isEmpty()
                    && parenthesisDepth == 0
                    && estimate(candidate) > TARGET_CHUNK_TOKENS) {
                addText(parts, current);
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(line);
            parenthesisDepth += count(line, '(') - count(line, ')');
        }

        addText(parts, current);
        return parts;
    }

    private List<String> hardFallback(String text) {
        return HARD_FALLBACK_SPLITTER.apply(List.of(new Document(text)))
                .stream()
                .map(Document::getText)
                .filter(part -> part != null && !part.isBlank())
                .toList();
    }

    private List<ChunkDraft> packUnits(List<TextUnit> units) {
        List<ChunkDraft> chunks = new ArrayList<>();
        ChunkDraft current = null;

        for (TextUnit unit : units) {
            if (unit.startsNewSection()) {
                addDraft(chunks, current);
                BoundaryType boundary = chunks.isEmpty()
                        ? BoundaryType.DOCUMENT_START
                        : BoundaryType.NEW_SECTION;
                current = ChunkDraft.from(unit, boundary);
                continue;
            }

            if (current == null) {
                BoundaryType boundary = chunks.isEmpty()
                        ? BoundaryType.DOCUMENT_START
                        : BoundaryType.SAME_TOPIC_TOKEN_LIMIT;
                current = ChunkDraft.from(unit, boundary);
                continue;
            }

            String candidate = current.text() + unit.separatorBefore() + unit.text();
            int candidateTokens = estimate(candidate);
            if (unit.startsSubsection()) {
                if (shouldAppend(current, unit, candidateTokens)) {
                    current = current.append(unit, candidate, candidateTokens);
                } else {
                    chunks.add(current);
                    current = ChunkDraft.from(unit, BoundaryType.SAME_SECTION_STRUCTURAL_SPLIT);
                }
                continue;
            }

            if (shouldAppend(current, unit, candidateTokens)) {
                current = current.append(unit, candidate, candidateTokens);
            } else {
                chunks.add(current);
                current = ChunkDraft.from(unit, BoundaryType.SAME_TOPIC_TOKEN_LIMIT);
            }
        }

        addDraft(chunks, current);
        return chunks;
    }

    private boolean shouldAppend(ChunkDraft current, TextUnit next, int candidateTokens) {
        if (candidateTokens <= TARGET_CHUNK_TOKENS) {
            return true;
        }
        if (candidateTokens > HARD_MAX_CHUNK_TOKENS) {
            return false;
        }

        // The target is soft. A modest overflow is better than producing a tiny
        // child-label/code/list fragment that has little retrieval value alone.
        return current.contentTokenCount() < MIN_PREFERRED_CHUNK_TOKENS
                || estimate(next.text()) < MIN_USEFUL_CHUNK_TOKENS;
    }

    private List<ChunkDraft> rebalanceUndersizedChunks(List<ChunkDraft> original) {
        List<ChunkDraft> chunks = new ArrayList<>(original);
        int index = 0;

        while (index < chunks.size()) {
            ChunkDraft current = chunks.get(index);
            if (current.contentTokenCount() >= MIN_PREFERRED_CHUNK_TOKENS) {
                index++;
                continue;
            }

            if (index > 0
                    && current.boundary().sameSectionSplit()
                    && sameSection(chunks.get(index - 1), current)) {
                ChunkDraft merged = merge(chunks.get(index - 1), current);
                if (merged.contentTokenCount() <= HARD_MAX_CHUNK_TOKENS) {
                    chunks.set(index - 1, merged);
                    chunks.remove(index);
                    index = Math.max(0, index - 1);
                    continue;
                }
            }

            if (index + 1 < chunks.size()
                    && chunks.get(index + 1).boundary().sameSectionSplit()
                    && sameSection(current, chunks.get(index + 1))) {
                ChunkDraft merged = merge(current, chunks.get(index + 1));
                if (merged.contentTokenCount() <= HARD_MAX_CHUNK_TOKENS) {
                    chunks.set(index, merged);
                    chunks.remove(index + 1);
                    continue;
                }
            }
            index++;
        }

        return chunks;
    }

    private List<ChunkDraft> applyConditionalOverlap(List<ChunkDraft> chunks) {
        if (chunks.size() < 2) {
            return chunks;
        }

        List<ChunkDraft> result = new ArrayList<>(chunks.size());
        result.add(chunks.getFirst());

        for (int index = 1; index < chunks.size(); index++) {
            ChunkDraft previous = chunks.get(index - 1);
            ChunkDraft current = chunks.get(index);

            if (!current.boundary().sameSectionSplit()
                    || !sameSection(previous, current)) {
                result.add(current);
                continue;
            }

            String overlap = current.boundary() == BoundaryType.SAME_SECTION_STRUCTURAL_SPLIT
                    && previous.sectionTitle() != null
                    && estimate(previous.sectionTitle()) <= MAX_OVERLAP_TOKENS
                            ? previous.sectionTitle()
                            : selectOverlap(previous);
            if (overlap.isBlank()
                    && previous.sectionTitle() != null
                    && estimate(previous.sectionTitle()) <= MAX_OVERLAP_TOKENS) {
                // A parent title is a safe context bridge when copying the
                // trailing sentence or complete structure is not possible.
                overlap = previous.sectionTitle();
            }
            String separator = previous.containsCode() && current.containsCode() ? "\n" : "\n\n";
            String combined = overlap.isBlank() ? current.text() : overlap + separator + current.text();
            int overlapTokens = estimate(overlap);

            if (overlap.isBlank() || estimate(combined) > HARD_MAX_CHUNK_TOKENS) {
                result.add(current);
            } else {
                result.add(current.withOverlap(combined, overlapTokens));
            }
        }
        return result;
    }

    private String selectOverlap(ChunkDraft previous) {
        String trailingBlock = trailingBlock(previous.text());
        BlockType type = classify(trailingBlock);

        if (type == BlockType.CODE || type == BlockType.LIST) {
            return estimate(trailingBlock) <= MAX_OVERLAP_TOKENS ? trailingBlock : "";
        }

        List<String> sentenceParts = sentences(trailingBlock.replaceAll("\\s+", " ").strip());
        List<String> selected = new ArrayList<>();
        for (int index = sentenceParts.size() - 1; index >= 0; index--) {
            String candidate = selected.isEmpty()
                    ? sentenceParts.get(index)
                    : sentenceParts.get(index) + " " + String.join(" ", selected);
            if (estimate(candidate) > MAX_OVERLAP_TOKENS) {
                break;
            }
            selected.addFirst(sentenceParts.get(index));
        }
        return String.join(" ", selected);
    }

    private List<Document> toDocuments(List<ChunkDraft> drafts) {
        List<Document> documents = new ArrayList<>(drafts.size());
        Map<Integer, Integer> chunksPerStartPage = new HashMap<>();
        for (ChunkDraft draft : drafts) {
            chunksPerStartPage.merge(draft.startPage(), 1, Integer::sum);
        }
        Map<Integer, Integer> nextPageIndex = new HashMap<>();

        for (int index = 0; index < drafts.size(); index++) {
            ChunkDraft draft = drafts.get(index);
            ChunkDraft previous = index == 0 ? null : drafts.get(index - 1);
            Map<String, Object> metadata = new HashMap<>(draft.sources().getFirst().metadata());
            List<Integer> pageNumbers = draft.sources().stream().map(SourceRef::pageNumber).distinct().toList();
            List<String> sourceIds = draft.sources().stream().map(SourceRef::documentId).distinct().toList();
            int pageChunkIndex = nextPageIndex.merge(draft.startPage(), 1, Integer::sum) - 1;
            boolean continuesFromPreviousPage = previous != null
                    && sameSection(previous, draft)
                    && draft.startPage() > previous.startPage();

            metadata.put(LibraryDocumentMetadata.SOURCE_DOCUMENT_ID, sourceIds.getFirst());
            metadata.put(LibraryDocumentMetadata.SOURCE_DOCUMENT_IDS, sourceIds);
            metadata.put(LibraryDocumentMetadata.SOURCE_PAGE_COUNT, sourceIds.size());
            metadata.put(LibraryDocumentMetadata.PAGE_NUMBER, draft.startPage());
            metadata.put(LibraryDocumentMetadata.START_PAGE_NUMBER, draft.startPage());
            metadata.put(LibraryDocumentMetadata.END_PAGE_NUMBER, draft.endPage());
            metadata.put(LibraryDocumentMetadata.PAGE_NUMBERS, pageNumbers);
            metadata.put(LibraryDocumentMetadata.SPANS_PAGES, pageNumbers.size() > 1);
            metadata.put(LibraryDocumentMetadata.PAGE_CHUNK_INDEX, pageChunkIndex);
            metadata.put(LibraryDocumentMetadata.PAGE_CHUNK_COUNT, chunksPerStartPage.get(draft.startPage()));
            metadata.put(LibraryDocumentMetadata.CONTAINS_CODE, draft.containsCode());
            metadata.put(LibraryDocumentMetadata.CONTAINS_LIST, draft.containsList());
            metadata.put(LibraryDocumentMetadata.CONTENT_TOKEN_COUNT, draft.contentTokenCount());
            metadata.put(LibraryDocumentMetadata.ESTIMATED_TOKEN_COUNT, estimate(draft.text()));
            metadata.put(LibraryDocumentMetadata.CHUNK_CHARACTER_COUNT, draft.text().length());
            metadata.put(LibraryDocumentMetadata.SECTION_TITLE, Objects.requireNonNullElse(draft.sectionTitle(), ""));
            metadata.put(LibraryDocumentMetadata.SUBSECTION_TITLES, draft.subsectionTitles());
            List<String> headingPath = new ArrayList<>();
            if (draft.sectionTitle() != null && !draft.sectionTitle().isBlank()) {
                headingPath.add(draft.sectionTitle());
            }
            headingPath.addAll(draft.subsectionTitles());
            metadata.put(LibraryDocumentMetadata.HEADING_PATH, List.copyOf(headingPath));
            metadata.put(LibraryDocumentMetadata.MAX_HEADING_LEVEL, draft.maxHeadingLevel());
            metadata.put(LibraryDocumentMetadata.STRUCTURAL_BOUNDARY, draft.boundary().value());
            metadata.put(LibraryDocumentMetadata.STARTS_NEW_TOPIC,
                    draft.boundary() == BoundaryType.NEW_SECTION
                            || draft.boundary() == BoundaryType.DOCUMENT_START && draft.containsHeading());
            metadata.put(LibraryDocumentMetadata.CONTINUES_FROM_PREVIOUS_PAGE, continuesFromPreviousPage);
            metadata.put(LibraryDocumentMetadata.OVERLAP_APPLIED, draft.overlapTokenCount() > 0);
            metadata.put(LibraryDocumentMetadata.OVERLAP_TOKEN_COUNT, draft.overlapTokenCount());
            metadata.put(
                    LibraryDocumentMetadata.SOURCE_ORIGINAL_CHARACTER_COUNT,
                    sumSourceMetric(draft.sources(), LibraryDocumentMetadata.SOURCE_ORIGINAL_CHARACTER_COUNT)
            );
            metadata.put(
                    LibraryDocumentMetadata.SOURCE_CLEANED_CHARACTER_COUNT,
                    sumSourceMetric(draft.sources(), LibraryDocumentMetadata.SOURCE_CLEANED_CHARACTER_COUNT)
            );
            if (draft.overlapTokenCount() > 0) {
                metadata.put(LibraryDocumentMetadata.OVERLAP_SOURCE_CHUNK_INDEX, index - 1);
            }

            documents.add(new Document(draft.text(), metadata));
        }
        return documents;
    }

    private ChunkDraft merge(ChunkDraft left, ChunkDraft right) {
        String separator = left.containsCode() && right.containsCode() ? "\n" : "\n\n";
        String text = left.text() + separator + right.text();
        return new ChunkDraft(
                text,
                left.containsCode() || right.containsCode(),
                left.containsList() || right.containsList(),
                left.containsHeading() || right.containsHeading(),
                left.sectionTitle(),
                mergeTitles(left.subsectionTitles(), right.subsectionTitles()),
                Math.max(left.maxHeadingLevel(), right.maxHeadingLevel()),
                left.boundary(),
                mergeSources(left.sources(), right.sources()),
                estimate(text),
                0
        );
    }

    private BlockType classify(String block) {
        if (isCodeBlock(block)) {
            return BlockType.CODE;
        }
        List<String> lines = block.lines().map(String::strip).filter(line -> !line.isBlank()).toList();
        boolean bulletList = !lines.isEmpty()
                && lines.stream().allMatch(line -> LIST_ITEM.matcher(line).matches())
                && (lines.size() > 1 || startsWithBullet(lines.getFirst()));
        if (bulletList) {
            return BlockType.LIST;
        }
        if (isHeading(block)) {
            return BlockType.HEADING;
        }
        if (!lines.isEmpty() && lines.stream().allMatch(line -> LIST_ITEM.matcher(line).matches())) {
            return BlockType.LIST;
        }
        return BlockType.PROSE;
    }

    private boolean isHeading(String block) {
        return headingLevel(block) > 0;
    }

    private int headingLevel(String block) {
        String candidate = block.strip();
        if (candidate.contains("\n") || candidate.length() > 140) {
            return 0;
        }
        Matcher numbered = NUMBERED_HEADING.matcher(candidate);
        if (numbered.matches()) {
            return (int) numbered.group(1).chars().filter(character -> character == '.').count() + 1;
        }
        Matcher markdown = MARKDOWN_HEADING.matcher(candidate);
        if (markdown.matches()) {
            return markdown.group(1).length();
        }
        if (COMMON_CHILD_HEADING.matcher(candidate).matches()) {
            return 2;
        }
        if (".?!;:{}".indexOf(candidate.charAt(candidate.length() - 1)) >= 0) {
            return 0;
        }

        String[] words = candidate.split("\\s+");
        if (words.length > 14) {
            return 0;
        }
        long meaningfulWords = java.util.Arrays.stream(words)
                .filter(word -> word.chars().anyMatch(Character::isLetter))
                .count();
        long titleCaseWords = java.util.Arrays.stream(words)
                .filter(word -> {
                    int firstLetter = word.codePoints().filter(Character::isLetter).findFirst().orElse(-1);
                    return firstLetter >= 0 && Character.isUpperCase(firstLetter);
                })
                .count();
        // Short title-like, unnumbered labels (Concept, How it works, Example,
        // Engineering note...) are children of the current numbered section.
        return meaningfulWords > 0 && titleCaseWords * 2 >= meaningfulWords ? 2 : 0;
    }

    private boolean startsWithBullet(String line) {
        return !line.isBlank() && "-*•‣◦▪▫●○■□‒–—⁃∙".indexOf(line.charAt(0)) >= 0;
    }

    private boolean isCodeBlock(String block) {
        List<String> lines = block.lines().map(String::strip).filter(line -> !line.isBlank()).toList();
        if (lines.isEmpty()) {
            return false;
        }
        long signals = lines.stream().filter(this::looksLikeCodeLine).count();
        return lines.size() == 1 ? signals == 1 : signals >= 2 || signals * 2 >= lines.size();
    }

    private boolean looksLikeCodeLine(String line) {
        if (line.equals("{")
                || line.equals("}")
                || line.startsWith("@")) {
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

    private List<String> sentences(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.ROOT);
        iterator.setText(text);
        List<String> result = new ArrayList<>();
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            String sentence = text.substring(start, end).strip();
            if (!sentence.isBlank()) {
                result.add(sentence);
            }
        }
        return result.isEmpty() ? List.of(text.strip()) : result;
    }

    private String trailingBlock(String text) {
        int separatorIndex = text.lastIndexOf("\n\n");
        return separatorIndex < 0 ? text.strip() : text.substring(separatorIndex + 2).strip();
    }

    private String separatorFor(BlockType type) {
        return switch (type) {
            case PROSE -> " ";
            case LIST, CODE -> "\n";
            case HEADING -> "\n\n";
        };
    }

    private int pageNumber(Document page, int fallback) {
        Object number = page.getMetadata().get(LibraryDocumentMetadata.PAGE_NUMBER);
        if (number instanceof Number numericPage) {
            return numericPage.intValue();
        }
        if (number != null) {
            try {
                return Integer.parseInt(number.toString());
            } catch (NumberFormatException ignored) {
                // Use extraction order when provider metadata is not numeric.
            }
        }
        return fallback;
    }

    private List<SourceRef> mergeSources(List<SourceRef> first, List<SourceRef> second) {
        Map<String, SourceRef> sources = new LinkedHashMap<>();
        first.forEach(source -> sources.put(source.documentId(), source));
        second.forEach(source -> sources.putIfAbsent(source.documentId(), source));
        return List.copyOf(sources.values());
    }

    private List<String> mergeTitles(List<String> first, List<String> second) {
        List<String> titles = new ArrayList<>(first);
        second.stream()
                .filter(title -> !title.isBlank() && !titles.contains(title))
                .forEach(titles::add);
        return List.copyOf(titles);
    }

    private int sumSourceMetric(List<SourceRef> sources, String key) {
        return sources.stream()
                .map(SourceRef::metadata)
                .map(metadata -> metadata.get(key))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .mapToInt(Number::intValue)
                .sum();
    }

    private boolean sameSection(ChunkDraft first, ChunkDraft second) {
        return Objects.equals(first.sectionTitle(), second.sectionTitle());
    }

    private int count(String text, char character) {
        return (int) text.chars().filter(value -> value == character).count();
    }

    private int estimate(String text) {
        return TOKEN_ESTIMATOR.estimate(text);
    }

    private void addText(List<String> parts, StringBuilder text) {
        if (!text.isEmpty() && !text.toString().isBlank()) {
            parts.add(text.toString().strip());
        }
    }

    private void addDraft(List<ChunkDraft> chunks, ChunkDraft draft) {
        if (draft != null && !draft.text().isBlank()) {
            chunks.add(draft);
        }
    }

    private enum BlockType {
        HEADING, PROSE, LIST, CODE
    }

    private enum BoundaryType {
        DOCUMENT_START("document_start"),
        NEW_SECTION("new_section"),
        SAME_SECTION_STRUCTURAL_SPLIT("same_section_structural_split"),
        SAME_TOPIC_TOKEN_LIMIT("same_topic_token_limit");

        private final String value;

        BoundaryType(String value) {
            this.value = value;
        }

        String value() {
            return value;
        }

        boolean sameSectionSplit() {
            return this == SAME_SECTION_STRUCTURAL_SPLIT
                    || this == SAME_TOPIC_TOKEN_LIMIT;
        }
    }

    private record SourceRef(int pageNumber, String documentId, Map<String, Object> metadata) {
    }

    private record TextBlock(String text, BlockType type, int headingLevel, SourceRef source) {
    }

    private record TextUnit(
            String text,
            BlockType type,
            boolean containsHeading,
            boolean startsNewSection,
            boolean startsSubsection,
            String sectionTitle,
            String subsectionTitle,
            int headingLevel,
            SourceRef source,
            String separatorBefore) {
    }

    private record ChunkDraft(
            String text,
            boolean containsCode,
            boolean containsList,
            boolean containsHeading,
            String sectionTitle,
            List<String> subsectionTitles,
            int maxHeadingLevel,
            BoundaryType boundary,
            List<SourceRef> sources,
            int contentTokenCount,
            int overlapTokenCount) {

        static ChunkDraft from(TextUnit unit, BoundaryType boundary) {
            return new ChunkDraft(
                    unit.text(),
                    unit.type() == BlockType.CODE,
                    unit.type() == BlockType.LIST,
                    unit.containsHeading(),
                    unit.sectionTitle(),
                    unit.subsectionTitle() == null || unit.subsectionTitle().isBlank()
                            ? List.of()
                            : List.of(unit.subsectionTitle()),
                    unit.headingLevel(),
                    boundary,
                    List.of(unit.source()),
                    TOKEN_ESTIMATOR.estimate(unit.text()),
                    0
            );
        }

        ChunkDraft append(TextUnit unit, String combinedText, int combinedTokens) {
            List<SourceRef> combinedSources = new ArrayList<>(sources);
            if (sources.stream().noneMatch(source -> source.documentId().equals(unit.source().documentId()))) {
                combinedSources.add(unit.source());
            }
            return new ChunkDraft(
                    combinedText,
                    containsCode || unit.type() == BlockType.CODE,
                    containsList || unit.type() == BlockType.LIST,
                    containsHeading || unit.containsHeading(),
                    sectionTitle,
                    appendTitle(subsectionTitles, unit.subsectionTitle()),
                    Math.max(maxHeadingLevel, unit.headingLevel()),
                    boundary,
                    List.copyOf(combinedSources),
                    combinedTokens,
                    0
            );
        }

        ChunkDraft withOverlap(String overlappedText, int tokens) {
            return new ChunkDraft(
                    overlappedText, containsCode, containsList, containsHeading,
                    sectionTitle, subsectionTitles, maxHeadingLevel,
                    boundary, sources, contentTokenCount, tokens
            );
        }

        private static List<String> appendTitle(List<String> titles, String title) {
            if (title == null || title.isBlank() || titles.contains(title)) {
                return titles;
            }
            List<String> result = new ArrayList<>(titles);
            result.add(title);
            return List.copyOf(result);
        }

        int startPage() {
            return sources.stream().mapToInt(SourceRef::pageNumber).min().orElse(1);
        }

        int endPage() {
            return sources.stream().mapToInt(SourceRef::pageNumber).max().orElse(startPage());
        }
    }
}
