# PDF Extraction and Chunking — Quick Notes

## Current pipeline

```text
Multipart PDF upload
        ↓
PagePdfDocumentReader
        ↓ page-level Spring AI Documents
PdfTextCleaningService
        ↓ normalized text with repeated margins removed
StructureAwareChunkingService
        ↓ ordered cross-page stream of heading/prose/list/code blocks
Structural packing
        ↓ numbered major headings start sections; child labels pack toward 250 tokens
TokenTextSplitter fallback
        ↓ only for one sentence or source line over 300 tokens
Conditional overlap + metadata
        ↓
inspect-all JSON response (no embedding or storage yet)
```

## Main classes

- `PagePdfDocumentReader`: uses PDFBox to extract PDF text, normally one Spring AI `Document` per page.
- `Document`: carries chunk text plus metadata such as filename and page numbers.
- `PdfTextCleaningService`: removes conservative PDF layout artifacts before splitting.
- `StructureAwareChunkingService`: processes all pages as one ordered structural stream, keeps headings with content, protects sentences/lists/code, balances small chunks, and adds conditional overlap.
- `TokenTextSplitter`: last-resort fallback only when one indivisible sentence or source line exceeds the hard limit.
- `PdfChunkService`: owns extraction, splitting, and chunk indexing.
- `PdfChunkController`: accepts a PDF and returns all generated chunks by default, or a limited debug preview when `previewLimit` is supplied.

## Splitter configuration

```text
preferredRange         = 180-250 tokens
hardMergeLimit         = 300 tokens
minimumUsefulChunk     = 80 tokens
maximumOverlap         = 24 tokens
maxNumChunks           = 1000
```

The target is approximately 180-250 tokens. The 250-token boundary is soft: the packer may grow to 300 tokens when that keeps a small child block attached and prevents a low-value standalone chunk. Chunks below the preferred 180-token size are merged with a same-parent neighbor whenever the merged result remains within 300 tokens. A genuinely separate parent section is never merged just to satisfy a size target.

Overlap is conditional rather than automatic:

- A chunk that starts with a major heading is a new section, so no previous text is copied into it.
- Child headings remain inside their numbered parent section. If that parent must split at a child boundary, the receiving chunk can get a small overlap because it is still the same parent topic.
- A chunk created because the 250-token target was reached within the same parent receives up to 24 estimated tokens from the previous chunk.
- The overlap prefers complete trailing sentences for prose. A trailing code block is copied only when the complete block fits the overlap budget; partial code is never copied.
- If neither fits, the compact parent title is used as the safe context bridge. No overlap is added when it would make the receiving chunk exceed the 300-token hard limit.

Metadata uses explicit names:

- `chunk_index`: global index across the PDF.
- `page_chunk_index`: index among chunks whose original content starts on that page.
- `page_chunk_count`: number of chunks whose original content starts on that page.
- `source_document_id`: ID of the cleaned page document.
- `source_document_ids`: every cleaned page document contributing content.
- `source_page_count`: number of contributing source pages.
- `source_file_name`: normalized source filename.
- `start_page_number`, `end_page_number`, `page_numbers`: precise page provenance, including cross-page chunks.
- `spans_pages`: whether the chunk contains original content from multiple pages.
- `continues_from_previous_page`: whether the same section continues after advancing to another page.
- `section_title`: major heading governing the chunk.
- `subsection_titles`: ordered subheadings included in the chunk.
- `heading_path`: major heading followed by the included subheadings.
- `max_heading_level`: deepest numbered or Markdown heading level included.
- `structural_boundary`: `document_start`, `new_section`, `same_section_structural_split`, or `same_topic_token_limit`.
- `starts_new_topic`: whether the chunk begins at a major-section boundary.
- `overlap_applied`: whether text from the preceding same-topic chunk was copied in.
- `overlap_token_count`: estimated size of only that copied text.
- `chunk_character_count`: number of characters in the final chunk, including overlap.
- `content_token_count`: token estimate before overlap.
- `estimated_token_count`: final token estimate after overlap.
- `contains_code`, `contains_list`: structural content flags.

## Cleaning before chunking

The cleaner performs these operations on page documents:

1. Normalizes Unicode, non-breaking spaces, and line endings.
2. Collapses repeated horizontal layout whitespace, including glyph-positioning gaps inside extracted code lines.
3. Detects repeated lines among the first/last three lines of each page. A line appearing on at least 60% of pages is removed as a likely header/footer; page counters are normalized before comparison. Recognized semantic headings such as `Concept` are protected from this removal.
4. Removes standalone numeric/Roman-numeral page counters from page margins even when their text differs on every page.
5. Joins wrapped prose lines while preserving blank-line paragraph boundaries.
6. Forces a boundary around numbered/Markdown headings when extraction lost the surrounding blank lines.
7. Joins wrapped continuation lines within a list item while keeping separate list items on separate lines.
8. Preserves code line breaks and relative indentation after removing the block's common PDF left margin.
9. Repairs a conservative lowercase word split such as `applica-\ntion`.

Cleaning preserves the original PDF metadata and adds:

- `text_cleaned`
- `source_original_character_count`
- `source_cleaned_character_count`
- `removed_margin_line_count`

The two `source_*_character_count` values total the contributing source page or pages. They are therefore repeated for chunks using the same source pages. `chunk_character_count` describes only the current final chunk.

## Structure-aware chunking

Cleaned pages are processed in order without resetting the section or current chunk at every page. Blocks are classified as heading, prose, list, or code. A top-level numbered heading or Markdown `#` starts a parent section. Decimal/Markdown child headings and short labels such as `Concept`, `How it works`, `Practical considerations`, `Example`, `Engineering note`, and `Chunking checkpoints` remain children. Consecutive headings stay attached to their first content block.

- Subsections accumulate in the current chunk while it remains near the 250-token target.
- If the next subsection would exceed that target, the chunk ends before the subsection at a `same_section_structural_split` boundary.
- Complete sentences are the preferred prose split units.
- A fitting list or code block stays intact up to the 300-token hard limit.
- ASCII and common Unicode bullets are recognized; oversized lists split between complete items.
- Oversized code prefers complete declaration/method boundaries and never splits while a multiline method signature has open parentheses.
- Code detection requires Java-like syntax as well as keywords or terminators, reducing false positives from ordinary prose ending in a semicolon.
- Tiny chunks are merged with a same-section neighbor when the result fits the hard limit. A short but meaningful heading section is retained rather than mixed into another topic.
- A small overlap is added only when one parent topic must split for size, including a split at a child boundary. It is never added across a new parent heading.
- A section can continue into or span the next page; all contributing page IDs and numbers are retained.

## Important limitations

- Only text-based PDFs are supported reliably; scanned books need OCR.
- No file, chunk, or embedding is persisted.
- No embedding API is called.
- Complex tables and multi-column layouts may need a layout-aware parser.
- The preview endpoint returns every generated chunk by default. Supply a positive `previewLimit` to inspect only the first few chunks of a large response.
