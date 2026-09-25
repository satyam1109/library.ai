# Library AI

Library AI is a learning-first project for building an AI-powered book learning assistant with Spring Boot, Spring AI, Gemini, React, PostgreSQL, and pgvector.

The project is intentionally built in small stages so that each AI concept is understood before higher-level abstractions are introduced.

## Learning notes

- [Spring AI Chat Model — Quick Notes](docs/chat-model-notes.md)
- [PDF Extraction and Chunking — Quick Notes](docs/document-chunking-notes.md)

## Current progress

- [x] Create the basic Spring Boot application.
- [x] Run the API on port `8090`.
- [x] Add a basic health/welcome endpoint.
- [x] Integrate a Gemini chat model through Spring AI.
- [x] Add separate in-memory conversation history for each book chat ID.
- [x] Add reusable Library AI system instructions to every prompt.
- [x] Return prompt, completion, and total token usage with each answer.
- [x] Upload a text-based PDF and preview token-based chunks.
- [x] Clean common PDF whitespace, wrapping, and repeated margins before chunking.
- [x] Keep headings and code together with structure-aware chunk packing.
- [x] Generate document and query embeddings with `gemini-embedding-001`.
- [x] Set up PostgreSQL 17 and pgvector with Docker Compose.
- [x] Store chunk text, metadata, and 768-dimensional vectors idempotently.
- [x] Group every chunk under a stable parent `documentId`.
- [x] Retrieve the top matching chunks with cosine similarity.
- [x] Extract and chunk PDF text.
- [x] Generate a document-grounded Gemini answer with inspectable sources.
- [x] Create persistent chats and attach up to three documents to each chat.
- [x] Persist RAG messages, token usage, and cited evidence in PostgreSQL.
- [x] Restrict every chat retrieval to its server-owned attached documents.
- [x] Add a responsive, light React UI connected to upload, catalog, and chat APIs.

## Current architecture

```text
HTTP client
    ↓ POST /api/ai/chat
AiChatController
    ↓
AiChatService
    ↓
ChatMemory (keyed by the book's chatId)
    ↓ SystemMessage + retained messages become a Prompt
Spring AI ChatModel
    ↓
GoogleGenAiChatModel
    ↓ HTTPS
Gemini Developer API
```

`ChatModel` is Spring AI's provider-neutral interface. The Google GenAI starter supplies its Gemini implementation through Spring Boot auto-configuration. It also supplies an in-memory `ChatMemory`; each `chatId` is a separate conversation. Application code therefore does not need to construct Google-specific HTTP requests or parse provider-specific JSON.

The document-grounded RAG path is separate from the basic memory-only chat:

```text
PDF upload
    -> existing clean + structure-aware chunking pipeline
    -> create/reuse parent documentId in library_documents
    -> Gemini RETRIEVAL_DOCUMENT embeddings
    -> PostgreSQL library_chunks (documentId + text + metadata + vector)

Question + chatId
    -> load the chat's 1-3 attached documentIds from PostgreSQL
    -> Gemini RETRIEVAL_QUERY embedding
    -> one globally ranked pgvector search filtered to the selected documentIds
    -> top matching original chunks + metadata + relevance score
    -> grounded prompt containing numbered sources
    -> Gemini chat model
    -> answer + source chunks + token usage
```

A chat owns at least one and at most three document attachments. The client sends only the `chatId` and question; the server loads the allowed document IDs and applies the pgvector `IN` filter. This prevents a client from broadening retrieval beyond the chat. Retrieved text is treated as untrusted reference material, and Gemini is instructed to answer only from those sources and cite them as `[Source N]`.

## Technology versions

| Technology | Version | Reason |
| --- | --- | --- |
| Java | 21 target | Long-term-support Java baseline |
| Spring Boot | 4.1.1 | Current project foundation |
| Spring AI | 2.0.1 | Stable release compatible with Spring Boot 4.1.x |
| Gemini chat model | `gemini-3.5-flash-lite` | Existing text-generation model |
| Gemini embedding model | `gemini-embedding-001` | One embedding space for both documents and questions |
| PostgreSQL image | `pgvector/pgvector:pg17` | Local PostgreSQL 17 image with pgvector included |

The Spring AI BOM manages compatible versions of all Spring AI modules. The embedding and pgvector starters deliberately have no separate version so they stay on Spring AI `2.0.1`.

The retrieval phase adds:

- `spring-ai-starter-model-google-genai-embedding`
- `spring-ai-starter-vector-store-pgvector`

The pgvector starter supplies the PostgreSQL JDBC support used by the application.

## Configuration

The tracked configuration is in `src/main/resources/application.properties`. It imports an optional local secret file from the project root:

```properties
spring.config.import=optional:file:./application-secret.properties
```

Create `application-secret.properties` in the project root:

```properties
spring.ai.google.genai.api-key=YOUR_GEMINI_API_KEY
```

The root secret file is ignored by Git. Never place the actual key in source code, tracked configuration, frontend code, or documentation.

The embedding client reuses this exact value through a property alias:

```properties
spring.ai.google.genai.embedding.api-key=${spring.ai.google.genai.api-key}
```

This does not create or require a second API key.

The current model options are:

```properties
spring.ai.google.genai.chat.model=gemini-3.5-flash-lite
spring.ai.google.genai.chat.temperature=0.2
spring.ai.google.genai.chat.max-output-tokens=800
```

- `model` selects the remote Gemini model.
- `temperature` controls variation in generated output; `0.2` favors focused responses.
- `max-output-tokens` allows moderately detailed teaching answers while keeping response length and quota usage bounded.

The embedding/vector-store configuration is:

```properties
spring.ai.google.genai.embedding.text.model=gemini-embedding-001
spring.ai.google.genai.embedding.text.task-type=RETRIEVAL_DOCUMENT
spring.ai.google.genai.embedding.text.dimensions=768

spring.datasource.url=jdbc:postgresql://localhost:5433/library_ai
spring.datasource.username=library_ai
spring.datasource.password=library_ai

spring.ai.vectorstore.pgvector.initialize-schema=true
spring.ai.vectorstore.pgvector.remove-existing-vector-store-table=false
spring.ai.vectorstore.pgvector.schema-name=public
spring.ai.vectorstore.pgvector.table-name=library_chunks
spring.ai.vectorstore.pgvector.index-type=HNSW
spring.ai.vectorstore.pgvector.distance-type=COSINE_DISTANCE
spring.ai.vectorstore.pgvector.dimensions=768
```

Documents use `RETRIEVAL_DOCUMENT`; questions use `RETRIEVAL_QUERY`. A small `EmbeddingModel` adapter selects the correct task for each operation while keeping both sides on `gemini-embedding-001` with 768 dimensions. The reduced dimension stays within pgvector's HNSW limits and reduces local storage compared with the model's largest output.

Database values can be overridden without changing source code through `LIBRARY_AI_DB_URL`, `LIBRARY_AI_DB_USERNAME`, and `LIBRARY_AI_DB_PASSWORD`.

## Start PostgreSQL and the application

Start the persistent local database:

```bash
docker compose up -d
docker compose ps
```

The compose file maps host port `5433` to PostgreSQL's container port `5432`, creates the `library_ai` database, and persists data in the `library_ai_pgdata` named volume. Its initialization script enables `vector`, `hstore`, and `uuid-ossp`.

Then start Spring Boot:

```bash
./mvnw spring-boot:run
```

Stop the services with:

```bash
docker compose down
```

`docker compose down` preserves the named database volume. Add `--volumes` only when you intentionally want to delete all locally indexed chunks.

## Run the application

```bash
./mvnw spring-boot:run
```

The application listens on `http://localhost:8090`.

Run the React development UI in a second terminal:

```bash
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173`. Vite proxies `/api` requests to the Spring Boot application on port `8090`, so the browser never receives the Gemini key or database credentials. To point the UI at another backend while developing, set `VITE_BACKEND_TARGET`, for example:

```bash
VITE_BACKEND_TARGET=http://localhost:8091 npm run dev
```

Create a production frontend bundle with `npm run build`. The generated files are written to `frontend/dist` and are intentionally not committed.

## API endpoints

### Welcome

```bash
curl http://localhost:8090/api/welcome
```

Expected response:

```json
{"message":"Library AI backend is running"}
```

### Gemini chat

```bash
curl -X POST http://localhost:8090/api/ai/chat \
  -H 'Content-Type: application/json' \
  -d '{"chatId":"book-chat-101","message":"Explain dependency injection in two short sentences."}'
```

Response shape:

```json
{
  "answer": "...",
  "promptTokens": 42,
  "completionTokens": 18,
  "totalTokens": 60
}
```

- `promptTokens` counts everything sent to Gemini for this call, including retained chat history.
- `completionTokens` counts Gemini's generated response.
- `totalTokens` is the combined usage for this call.

These values come from `ChatResponse.getMetadata().getUsage()`. They describe the current model call, not the lifetime total for the `chatId`.

A null or blank `chatId` or `message` is rejected locally with HTTP `400`; it does not consume a Gemini API call. Reuse the same `chatId` for follow-up questions about the same book, and use a different ID for another book.

### Clear one book's chat history

```bash
curl -i -X DELETE http://localhost:8090/api/ai/chat/book-chat-101
```

The successful response is HTTP `204 No Content`. It clears only that `chatId`.

### Preview PDF chunks

```bash
curl -X POST 'http://localhost:8090/api/documents/chunks/preview' \
  -F 'file=@/absolute/path/to/book.pdf'
```

The response reports how many page documents and chunks were created, then returns the requested previews:

```json
{
  "documentId": "<uuid>",
  "fileName": "book.pdf",
  "sourceDocumentCount": 12,
  "totalChunkCount": 18,
  "previewCount": 18,
  "chunks": [
    {
      "chunkIndex": 0,
      "text": "...",
      "tokenCount": 287,
      "contentTokenCount": 287,
      "characterCount": 1138,
      "sectionTitle": "1. Java Execution Model",
      "subsectionTitles": ["1.1 JDK, JRE and JVM"],
      "headingPath": ["1. Java Execution Model", "1.1 JDK, JRE and JVM"],
      "maxHeadingLevel": 2,
      "structuralBoundary": "document_start",
      "containsCode": false,
      "containsList": false,
      "overlapApplied": false,
      "overlapTokenCount": 0,
      "pageNumbers": [1, 2],
      "spansPages": true,
      "continuesFromPreviousPage": false,
      "metadata": {
        "chunk_index": 0,
        "page_number": 1,
        "page_chunk_index": 0,
        "page_chunk_count": 1,
        "start_page_number": 1,
        "end_page_number": 2,
        "page_numbers": [1, 2],
        "spans_pages": true,
        "source_file_name": "book.pdf",
        "source_document_ids": ["page-document-1", "page-document-2"],
        "section_title": "1. Java Execution Model",
        "subsection_titles": ["1.1 JDK, JRE and JVM"],
        "heading_path": ["1. Java Execution Model", "1.1 JDK, JRE and JVM"],
        "max_heading_level": 2,
        "structural_boundary": "document_start",
        "contains_code": false,
        "contains_list": false,
        "content_token_count": 287,
        "estimated_token_count": 287,
        "chunk_character_count": 1138,
        "source_cleaned_character_count": 2859,
        "starts_new_topic": true,
        "overlap_applied": false,
        "overlap_token_count": 0
      }
    }
  ]
}
```

When `previewLimit` is omitted, every generated chunk is included in the response. Add a positive limit, such as `?previewLimit=5`, when you only want to inspect the first few chunks. A limit larger than the number of generated chunks simply returns all available chunks. The current 20 MB upload endpoint is for learning and inspection: it does not store the uploaded PDF or generate embeddings.

Before splitting, extracted pages pass through a conservative cleaning stage. It collapses PDF layout whitespace, joins fake prose wraps and list continuations, preserves real paragraph boundaries, normalizes code indentation relative to its PDF left margin, repairs simple line-wrap hyphenation, and removes repeated margins plus standalone page counters. Cleaning statistics are copied into each chunk's metadata.

Chunk metadata consistently uses snake_case because it will later be stored as JSON in pgvector. `chunk_index` is global within the PDF. Cross-page chunks retain `page_numbers`, `start_page_number`, `end_page_number`, and `source_document_ids`. `source_cleaned_character_count` totals the cleaned contributing source pages, while `chunk_character_count` describes only the final chunk. The debug DTO repeats the most useful values as camelCase top-level fields for easy inspection.

The structure-aware chunker processes every cleaned page as one ordered stream, so a section can continue across page boundaries. Only a numbered top-level heading or Markdown `#` starts a parent retrieval section. Smaller labels such as `Concept`, `How it works`, `Practical considerations`, `Example`, `Engineering note`, and `Chunking checkpoints` remain children and accumulate toward a soft 250-token target. The packer may grow to 300 tokens when doing so avoids a small standalone child chunk. Prose splits between complete sentences, Unicode-aware lists split between complete items, and oversized code splits at safe declaration/method or line boundaries without cutting multiline signatures. Up to 24 tokens of overlap are added only when the same parent must split, never when a new parent begins. Same-parent chunks below the preferred 180-token size are merged when safe.

### Ingest a PDF into pgvector

```bash
curl -X POST http://localhost:8090/api/documents/ingest \
  -F 'file=@/absolute/path/to/book.pdf'
```

Example response:

```json
{
  "fileName": "book.pdf",
  "documentFingerprint": "<sha-256>",
  "sourceDocumentCount": 50,
  "generatedChunkCount": 52,
  "storedChunkCount": 52,
  "skippedAsDuplicate": false
}
```

The service creates one parent row in `library_documents` and returns its UUID as `documentId`. It hashes the PDF and final chunk set, then gives every chunk that same parent ID plus its own deterministic UUID. Re-uploading unchanged content reuses the document ID, returns `skippedAsDuplicate: true`, and does not call Gemini or insert duplicate vectors. If chunking changes for the same document, the new batch is stored successfully before its older vectors are removed.

List all indexed parent documents without loading their chunks:

```bash
curl http://localhost:8090/api/documents
```

Each catalog row reports its `documentId`, file name, content fingerprint, ingestion status, chunk count, and timestamps. `PROCESSING`, `READY`, and `FAILED` make ingestion state inspectable.

Each `library_chunks` row contains:

- the original chunk text in `content`;
- the complete chunk metadata as JSON;
- its 768-dimensional embedding;
- a stable chunk ID.

Ingestion adds `document_id`, `document_fingerprint`, `ingestion_fingerprint`, `chunk_fingerprint`, `embedding_model`, and `embedding_dimensions` without discarding page, section, hierarchy, token, code/list, or overlap metadata produced by the chunker. An expression index on `metadata.document_id` keeps ownership filtering efficient.

### Inspect similarity retrieval

```bash
curl -X POST http://localhost:8090/api/retrieval/search \
  -H 'Content-Type: application/json' \
  -d '{"documentId":"<uuid-from-ingestion>","question":"How does HashMap work?","topK":5}'
```

The response contains ranked original chunk text, all metadata, and a cosine relevance `score`. `topK` defaults to 5 and accepts values from 1 through 20. `documentId` is required, ensuring retrieval cannot accidentally mix chunks from different documents:

```json
{
  "documentId": "<uuid-from-ingestion>",
  "question": "How does HashMap work?",
  "topK": 5
}
```

Live verification with the 50-page Java test PDF stored all 52 vectors under one document ID. The top result for `How does HashMap work?` was section `18. HashMap Internals` on page 18 with relevance score `0.7225`.

### Generate a grounded RAG answer

```bash
curl -X POST http://localhost:8090/api/rag/answer \
  -H 'Content-Type: application/json' \
  -d '{
    "documentId":"<uuid-from-ingestion>",
    "question":"How does HashMap work?",
    "topK":3
  }'
```

The operation is deliberately ordered:

1. Verify the selected document exists and has status `READY`.
2. Embed the question with `RETRIEVAL_QUERY`.
3. Search only chunks whose metadata contains the selected `document_id`.
4. Build a grounded prompt with numbered source text, section titles, and pages.
5. Ask the existing Gemini chat model to answer only from that evidence.
6. Return the answer, retrieved chunks, relevance scores, metadata, and token usage.

This endpoint is currently stateless. It does not mix the basic `/api/ai/chat` memory into RAG, which avoids leaking conversation context between documents. An unknown, malformed, or not-yet-ready `documentId` is rejected before an embedding or chat call is made.

### Chat with up to three documents

Create a persistent chat:

```bash
curl -X POST http://localhost:8090/api/chats \
  -H 'Content-Type: application/json' \
  -d '{}'
```

Attach one or more existing documents, up to three total:

```bash
curl -X POST http://localhost:8090/api/chats/<chat-id>/documents \
  -H 'Content-Type: application/json' \
  -d '{
    "documentIds":[
      "<first-ready-document-uuid>",
      "<second-ready-document-uuid>"
    ]
  }'
```

Ask a question in that chat:

```bash
curl -X POST http://localhost:8090/api/chats/<chat-id>/messages \
  -H 'Content-Type: application/json' \
  -d '{
    "message":"Compare how these documents explain collection behavior.",
    "topK":5
  }'
```

For every message, the backend:

1. Loads the chat and its one to three attached, `READY` documents from PostgreSQL.
2. Uses recent conversation context to make follow-up retrieval queries clearer.
3. Creates one query embedding and performs one globally ranked similarity search across only the selected document IDs.
4. Sends the retrieved text, source metadata, prior messages, and current question to Gemini.
5. Returns the grounded answer, ranked sources, relevance scores, and model token usage.
6. Persists the user question, assistant answer, token usage, and exact cited chunks.

Messages are isolated by `chatId`. Adding or removing a document increments the chat's `contextVersion`; older messages remain visible, but only messages from the current context version are supplied to Gemini. This prevents an answer from silently inheriting context from a document that has since been removed.

For interactive progress, the frontend sends the same request body to:

```http
POST /api/chats/{chatId}/messages/stream
Accept: text/event-stream
```

The endpoint returns server-sent events from real backend boundaries:

```text
progress: UNDERSTANDING  -> validate context and create the Gemini query embedding
progress: SEARCHING      -> query embedding is ready; pgvector begins similarity search
progress: GENERATING     -> top matching chunks are ready; Gemini begins writing
sources                  -> ranked chunks and metadata are ready for citation navigation
token                    -> one incremental Gemini answer fragment
result                    -> complete RagChatResponse with answer, sources, and tokens
failure                   -> safe error message plus the stage that failed
```

The JSON `/api/chats/{chatId}/messages` endpoint is available for non-streaming clients. The streaming endpoint uses Spring AI's `ChatModel.stream(Prompt)` with the same Gemini model, API key, prompt, retrieval results, and persisted history as the JSON flow. Streaming work runs on a bounded application executor rather than holding a servlet request thread during the external model calls.

The frontend stores only the last active `chatId` as a convenience. Chats, attachments, messages, citations, and usage are restored from PostgreSQL after refreshes or backend restarts. Uploading inside a chat uses one endpoint that performs idempotent ingestion and then attaches the resulting document, so an already-indexed PDF reuses its vectors.

### Frontend flow

```text
Create/load chats ─────────> GET/POST /api/chats
Load indexed documents ───> GET  /api/documents
Attach 1-3 documents ──────> POST /api/chats/{chatId}/documents
Upload into a chat ────────> POST /api/chats/{chatId}/documents/upload
Ask/follow up ─────────────> POST /api/chats/{chatId}/messages/stream
                              ↓
               progress → sources → answer fragments → result
```

The interface is intentionally light and minimal. It shows indexed chunk counts, enforces the three-document limit, displays active context, renders grounded answers progressively as safe GitHub-Flavored Markdown, and surfaces token usage for each successful answer. Citations such as `Source 1` are interactive: selecting one opens its exact retrieved passage in the evidence panel. The two highest-ranked sources are shown initially; any additional matches are available through **Show more sources**, and every source can still be expanded to inspect its complete retrieved chunk and page metadata.

The application uses a focused, viewport-fixed workspace. The document library
and navigation rail remain in place while the conversation scrolls independently.
The library sidebar can be minimized or expanded, and that preference is retained
in browser storage. Selecting an answer citation also opens a dedicated evidence
panel containing the source file, page, section, retrieved passage, and relevance.

## Book-scoped chat memory

For each request, the service:

1. Creates a Spring AI `UserMessage`.
2. Adds it to `ChatMemory` under the supplied `chatId`.
3. Gets that chat's retained messages and creates a `Prompt`.
4. Calls Gemini and receives an `AssistantMessage`.
5. Stores the assistant message under the same `chatId`.

Spring AI auto-configures `MessageWindowChatMemory` with an `InMemoryChatMemoryRepository`. Its default window retains at most 20 messages per conversation, preventing history from growing without a bound. This memory is shared by requests to the running application and is not tied to a browser session. It is erased when the application restarts and will later be replaced with persistent PostgreSQL-backed memory.

## System instructions

Every prompt begins with one Spring AI `SystemMessage` that defines Library AI's behavior: explain clearly, use supplied book context, and never claim access to missing book content. The message is kept outside `ChatMemory` and prepended when the prompt is created, so it appears exactly once on every model call.

```text
SystemMessage (Library AI instructions)
UserMessage / AssistantMessage history for this chatId
Current UserMessage
```

The system message guides behavior; it does not provide the actual contents of a book. Book text will be supplied later through retrieval-augmented generation.

## What we learned

### Chat model versus embedding model

A chat model accepts a prompt and generates text. An embedding model accepts text and returns a numeric vector used for semantic comparison. In this phase, embeddings retrieve source material but do not generate an answer.

### Why document and query tasks differ

Gemini supports asymmetric retrieval embeddings. Stored chunks are encoded as retrieval documents and questions are encoded as retrieval queries. They still use the same model and vector dimensions, so pgvector can compare them meaningfully.

### Why text and metadata are stored

The vector is only a search representation. RAG needs the original text for future prompt context and metadata for citations, book filtering, debugging, and UI display, so pgvector stores all three together.

### Local work versus model work

Spring MVC routing, PDF processing, hashing, pgvector storage/search, validation, and response construction happen locally. Chat calls and embedding generation cross the network to Gemini.

### Auto-configuration

Spring Boot detects the Google GenAI starters and creates both `GoogleGenAiChatModel` and `GoogleGenAiTextEmbeddingModel`. Application services use Spring AI's `ChatModel`, `EmbeddingModel`, and `VectorStore` abstractions. The retrieval adapter adds the document/query task distinction without changing the existing chunker.

### Token-conscious choices

- Blank prompts are rejected before the network call.
- The lightweight model is used for the basic operation.
- Chat output is capped at 500 tokens.
- Only the bounded message window for the requested `chatId` is sent.
- One reusable system message is prepended without being duplicated in memory.
- Token usage is returned so prompt growth can be measured while learning.

## Next learning step

Add chat rename/delete controls and evaluate retrieval and grounded-answer quality across varied PDFs with a repeatable RAG evaluation set.
