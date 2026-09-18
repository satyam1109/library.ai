package com.libraryai.document;

/**
 * Stable metadata names shared by cleaning, chunking, preview, and future
 * vector-store filtering. Metadata uses snake_case because it is persisted as
 * JSON; Java response fields continue to use normal camelCase.
 */
public final class LibraryDocumentMetadata {

    public static final String SOURCE_FILE_NAME = "source_file_name";
    public static final String PAGE_NUMBER = "page_number";
    public static final String START_PAGE_NUMBER = "start_page_number";
    public static final String END_PAGE_NUMBER = "end_page_number";
    public static final String PAGE_NUMBERS = "page_numbers";
    public static final String SPANS_PAGES = "spans_pages";
    public static final String SOURCE_DOCUMENT_ID = "source_document_id";
    public static final String SOURCE_DOCUMENT_IDS = "source_document_ids";
    public static final String SOURCE_PAGE_COUNT = "source_page_count";
    public static final String CHUNK_INDEX = "chunk_index";
    public static final String PAGE_CHUNK_INDEX = "page_chunk_index";
    public static final String PAGE_CHUNK_COUNT = "page_chunk_count";
    public static final String CONTAINS_CODE = "contains_code";
    public static final String CONTAINS_LIST = "contains_list";
    public static final String ESTIMATED_TOKEN_COUNT = "estimated_token_count";
    public static final String CONTENT_TOKEN_COUNT = "content_token_count";
    public static final String CHUNK_CHARACTER_COUNT = "chunk_character_count";
    public static final String STARTS_NEW_TOPIC = "starts_new_topic";
    public static final String SECTION_TITLE = "section_title";
    public static final String SUBSECTION_TITLES = "subsection_titles";
    public static final String HEADING_PATH = "heading_path";
    public static final String MAX_HEADING_LEVEL = "max_heading_level";
    public static final String STRUCTURAL_BOUNDARY = "structural_boundary";
    public static final String CONTINUES_FROM_PREVIOUS_PAGE = "continues_from_previous_page";
    public static final String OVERLAP_APPLIED = "overlap_applied";
    public static final String OVERLAP_TOKEN_COUNT = "overlap_token_count";
    public static final String OVERLAP_SOURCE_CHUNK_INDEX = "overlap_source_chunk_index";
    public static final String TEXT_CLEANED = "text_cleaned";
    public static final String SOURCE_ORIGINAL_CHARACTER_COUNT = "source_original_character_count";
    public static final String SOURCE_CLEANED_CHARACTER_COUNT = "source_cleaned_character_count";
    public static final String REMOVED_MARGIN_LINE_COUNT = "removed_margin_line_count";
    public static final String DOCUMENT_ID = "document_id";
    public static final String DOCUMENT_FINGERPRINT = "document_fingerprint";
    public static final String INGESTION_FINGERPRINT = "ingestion_fingerprint";
    public static final String CHUNK_FINGERPRINT = "chunk_fingerprint";
    public static final String EMBEDDING_MODEL = "embedding_model";
    public static final String EMBEDDING_DIMENSIONS = "embedding_dimensions";

    private LibraryDocumentMetadata() {
    }
}
