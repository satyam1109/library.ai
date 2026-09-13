# Library AI

Library AI is a learning-first project for building an AI-powered book learning assistant with Spring Boot, Spring AI, Gemini, React, PostgreSQL, and pgvector.

The project is intentionally built in small stages so that each AI concept is understood before higher-level abstractions are introduced.

## Learning notes

- [Spring AI Chat Model — Quick Notes](docs/chat-model-notes.md)

## Current progress

- [x] Create the basic Spring Boot application.
- [x] Run the API on port `8088`.
- [x] Add a basic health/welcome endpoint.
- [x] Integrate a Gemini chat model through Spring AI.
- [x] Add separate in-memory conversation history for each book chat ID.
- [x] Return prompt, completion, and total token usage with each answer.
- [ ] Generate and compare embeddings.
- [ ] Set up PostgreSQL and pgvector.
- [ ] Extract and chunk PDF text.
- [ ] Build retrieval-augmented generation (RAG).

## Current architecture

```text
HTTP client
    ↓ POST /api/ai/chat
AiChatController
    ↓
AiChatService
    ↓
ChatMemory (keyed by the book's chatId)
    ↓ retained messages become a Prompt
Spring AI ChatModel
    ↓
GoogleGenAiChatModel
    ↓ HTTPS
Gemini Developer API
```

`ChatModel` is Spring AI's provider-neutral interface. The Google GenAI starter supplies its Gemini implementation through Spring Boot auto-configuration. It also supplies an in-memory `ChatMemory`; each `chatId` is a separate conversation. Application code therefore does not need to construct Google-specific HTTP requests or parse provider-specific JSON.

## Technology versions

| Technology | Version | Reason |
| --- | --- | --- |
| Java | 21 target | Long-term-support Java baseline |
| Spring Boot | 4.1.1 | Current project foundation |
| Spring AI | 2.0.1 | Stable release compatible with Spring Boot 4.1.x |
| Gemini model | `gemini-3.5-flash-lite` | Current Flash-Lite model available to new Gemini users |

The Spring AI BOM manages compatible versions of the individual Spring AI modules. The application adds only `spring-ai-starter-model-google-genai`; it does not include embeddings, vector stores, databases, memory, agents, or MCP yet.

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

The current model options are:

```properties
spring.ai.google.genai.chat.model=gemini-3.5-flash-lite
spring.ai.google.genai.chat.temperature=0.2
spring.ai.google.genai.chat.max-output-tokens=300
```

- `model` selects the remote Gemini model.
- `temperature` controls variation in generated output; `0.2` favors focused responses.
- `max-output-tokens` limits response length and helps control quota usage.

## Run the application

```bash
./mvnw spring-boot:run
```

The application listens on `http://localhost:8090`.

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

## Book-scoped chat memory

For each request, the service:

1. Creates a Spring AI `UserMessage`.
2. Adds it to `ChatMemory` under the supplied `chatId`.
3. Gets that chat's retained messages and creates a `Prompt`.
4. Calls Gemini and receives an `AssistantMessage`.
5. Stores the assistant message under the same `chatId`.

Spring AI auto-configures `MessageWindowChatMemory` with an `InMemoryChatMemoryRepository`. Its default window retains at most 20 messages per conversation, preventing history from growing without a bound. This memory is shared by requests to the running application and is not tied to a browser session. It is erased when the application restarts and will later be replaced with persistent PostgreSQL-backed memory.

## What we learned

### Chat model versus embedding model

A chat model accepts a prompt and generates text. An embedding model accepts text and returns a numeric vector used for semantic comparison. This stage uses only a chat model.

### Local work versus model work

Spring MVC routing, JSON conversion, input validation, configuration, and response construction happen locally. Only `ChatModel.call(...)` crosses the network and invokes Gemini.

### Auto-configuration

Spring Boot detects the Google GenAI starter and its properties, creates a `GoogleGenAiChatModel`, and exposes it through the `ChatModel` interface. Our service depends on that interface instead of a Google-specific implementation.

### Token-conscious choices

- Blank prompts are rejected before the network call.
- The lightweight model is used for the basic operation.
- Output is capped at 300 tokens.
- Only the bounded message window for the requested `chatId` is sent.
- Token usage is returned so prompt growth can be measured while learning.

## Next learning step

Persist book and conversation data in PostgreSQL so chat history survives application restarts.
