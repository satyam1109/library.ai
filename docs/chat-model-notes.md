# Spring AI Chat Model — Quick Notes

## Current setup

| Component | Current choice |
| --- | --- |
| Java target | 21 |
| Spring Boot | 4.1.1 |
| Spring AI | 2.0.1 |
| Provider | Google GenAI |
| Remote model | `gemini-3.5-flash-lite` |

## Important classes

```text
ChatRequest
    → immutable HTTP input DTO

AiChatController
    → converts HTTP/JSON input into a service call

AiChatService
    → validates input and calls the AI model

ChatModel
    → provider-neutral Spring AI interface

GoogleGenAiChatModel
    → Gemini implementation of ChatModel

ChatResponse
    → immutable HTTP output DTO containing answer and token usage

AiChatResult
    → service result containing answer and per-call token usage

ChatMemory
    → stores messages separately under each book's chatId

MessageWindowChatMemory
    → keeps a bounded window of recent messages

SystemMessage
    → supplies Library AI's behavior instructions on every call
```

## Why request and response are records

`ChatRequest` and `ChatResponse` only carry data, so Java records are a good fit:

- Immutable after construction
- Concise constructor and accessors
- Automatic `equals`, `hashCode`, and `toString`
- Supported by Jackson for JSON conversion

Use records for simple DTOs. Prefer regular classes for mutable objects and JPA entities.

Record accessors do not use `get`:

```java
request.message();
response.answer();
```

## Request flow

```text
POST /api/ai/chat
        ↓
Tomcat and Spring MVC
        ↓ JSON → ChatRequest
AiChatController.chat(...)
        ↓
AiChatService.generateAnswer(...)
        ↓ local blank-input validation
ChatMemory.add(chatId, UserMessage)
        ↓
ChatMemory.get(chatId)
        ↓ SystemMessage + retained history → Prompt
ChatModel.call(prompt)
        ↓ network boundary
GoogleGenAiChatModel
        ↓ Google GenAI Java client + HTTPS
Gemini API
        ↓ generated text + response metadata
ChatResponse
        ↓ Java object → JSON
HTTP response
```

Gemini does not remember earlier API calls by itself. The application sends the retained messages again on every request. After Gemini responds, its `AssistantMessage` is saved so it is available for the next turn.

The current conversation key is the book's `chatId`, not an HTTP session ID. Reusing a chat ID continues that book's conversation; another chat ID creates isolated history.

## System message

The service prepends a reusable `SystemMessage` to every prompt. It defines the assistant's role and constraints but is deliberately not stored in `ChatMemory`:

```text
Prompt
├── SYSTEM: Library AI behavior instructions
├── USER / ASSISTANT: retained conversation turns
└── USER: current question
```

Keeping it outside memory prevents duplicate copies while ensuring it is always the first message sent to the model. A system prompt guides Gemini; it does not give Gemini access to book text that the application has not supplied.

Everything before `ChatModel.call(...)` is local. That call sends the prompt to Gemini and consumes remote API quota.

## Token usage metadata

The provider response contains usage metadata:

```java
Usage usage = response.getMetadata().getUsage();

usage.getPromptTokens();
usage.getCompletionTokens();
usage.getTotalTokens();
```

`promptTokens` includes the current user message and retained conversation history. `completionTokens` counts the generated answer. `totalTokens` is their combined usage for this model call; it is not a cumulative total for the conversation.

## How Gemini is integrated

The Maven dependency adds the provider implementation and its auto-configuration:

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-google-genai</artifactId>
</dependency>
```

At startup, Spring Boot:

1. Detects the Google GenAI starter.
2. Reads `spring.ai.google.genai.*` properties.
3. Creates the Google GenAI client using the API key.
4. Creates a `GoogleGenAiChatModel` bean.
5. Injects it wherever a `ChatModel` is required.

Conceptually:

```java
ChatModel chatModel = new GoogleGenAiChatModel(...);
```

Spring Boot performs this wiring through auto-configuration; our application does not instantiate it directly.

## Interface versus implementation versus remote model

```text
ChatModel
    → Spring AI Java interface

GoogleGenAiChatModel
    → Java implementation that talks to Google

gemini-3.5-flash-lite
    → remote model running on Google's infrastructure
```

Our service depends on the interface:

```java
private final ChatModel chatModel;
```

This reduces provider-specific coupling and makes testing or provider replacement easier.

## Configuration responsibilities

```properties
# Select the provider auto-configuration (Google is currently the default).
spring.ai.model.chat=google-genai

# Select the remote Gemini model used by that provider.
spring.ai.google.genai.chat.model=gemini-3.5-flash-lite

# Authenticate requests to Google.
spring.ai.google.genai.api-key=...

# Prefer focused output.
spring.ai.google.genai.chat.temperature=0.2

# Bound generated response length.
spring.ai.google.genai.chat.max-output-tokens=300
```

Provider and remote model are separate choices:

```text
spring.ai.model.chat
    → chooses Google, OpenAI, Ollama, etc.

spring.ai.<provider>.chat.model
    → chooses a model offered by that provider
```

## Multiple providers

### One provider active application-wide

Select the provider through configuration:

```properties
spring.ai.model.chat=google-genai
# or openai
# or ollama
```

Spring auto-configures one `ChatModel`, so normal constructor injection remains unambiguous.

### Several providers active simultaneously

Create named beans:

```text
geminiChatModel → GoogleGenAiChatModel
openAiChatModel → OpenAiChatModel
ollamaChatModel → OllamaChatModel
```

Use `@Qualifier` when a service always needs one provider:

```java
AiService(@Qualifier("geminiChatModel") ChatModel chatModel)
```

Use `@Primary` to define the default provider for unqualified injection.

For per-request selection, inject the models into a routing service and select them using a controlled enum such as `GEMINI`, `OPENAI`, or `OLLAMA`. Do not expose arbitrary Spring bean names to clients.

## Current limitations

- Requests are synchronous.
- Memory is currently in-process and is lost when the application restarts.
- The default window retains at most 20 messages for each chat ID.
- Concurrent backend instances do not share this in-memory history.
- Actual book content is not supplied yet; the system message only defines behavior.
- Token usage depends on metadata reported by the selected provider/model.
- No embeddings, vector database, or RAG exist yet.

## Key points to remember

1. Spring AI is an integration framework, not an AI model.
2. `ChatModel` is the common interface.
3. `GoogleGenAiChatModel` implements that interface for Gemini.
4. The starter dependency and configuration cause Spring Boot to create the implementation.
5. `ChatModel.call(...)` is the current external API-call boundary.
6. `spring.ai.model.chat` selects a provider; the provider-specific `chat.model` property selects its remote model.
7. Multiple simultaneous providers require named beans plus `@Qualifier`, `@Primary`, or explicit routing.
8. `ChatMemory` stores messages; Gemini only sees the messages placed in each new `Prompt`.
9. A book's `chatId` is used as the conversation ID, keeping different books isolated.
10. Token usage is model-call metadata; longer retained history generally increases prompt tokens.
11. A `SystemMessage` controls behavior and should appear before the conversation messages.
