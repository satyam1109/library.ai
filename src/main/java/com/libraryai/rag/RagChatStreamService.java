package com.libraryai.rag;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Runs the blocking RAG flow asynchronously and streams its real milestones. */
@Service
public class RagChatStreamService {

    private static final long STREAM_TIMEOUT_MILLIS = 180_000L;

    private final RagChatService ragChatService;
    private final TaskExecutor taskExecutor;

    public RagChatStreamService(
            RagChatService ragChatService,
            @Qualifier("ragTaskExecutor") TaskExecutor taskExecutor) {
        this.ragChatService = ragChatService;
        this.taskExecutor = taskExecutor;
    }

    public SseEmitter stream(RagChatRequest request) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        this.taskExecutor.execute(() -> runStream(request, emitter));
        return emitter;
    }

    private void runStream(RagChatRequest request, SseEmitter emitter) {
        AtomicReference<RagChatProgressStage> currentStage = new AtomicReference<>();
        try {
            RagChatResponse response = this.ragChatService.streamChat(
                    request,
                    stage -> {
                        currentStage.set(stage);
                        send(emitter, "progress", RagChatProgressEvent.from(stage));
                    },
                    sources -> send(emitter, "sources", new RagChatSourcesEvent(
                            sources.size(), sources
                    )),
                    text -> send(emitter, "token", new RagChatTokenEvent(text))
            );
            send(emitter, "result", response);
            emitter.complete();
        } catch (RuntimeException exception) {
            try {
                send(emitter, "failure", new RagChatFailureEvent(
                        currentStage.get(), safeMessage(exception)
                ));
                emitter.complete();
            } catch (RuntimeException sendFailure) {
                emitter.completeWithError(exception);
            }
        }
    }

    private void send(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException exception) {
            throw new IllegalStateException("The client disconnected from the RAG stream", exception);
        }
    }

    private String safeMessage(RuntimeException exception) {
        if (exception instanceof IllegalArgumentException && exception.getMessage() != null) {
            return exception.getMessage();
        }
        return "The request could not be completed. Please try again.";
    }
}
