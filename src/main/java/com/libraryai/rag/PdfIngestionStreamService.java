package com.libraryai.rag;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Streams real PDF processing and embedding progress to the upload UI. */
@Service
public class PdfIngestionStreamService {

    private static final long STREAM_TIMEOUT_MILLIS = 600_000L;

    private final PdfIngestionService ingestionService;
    private final LibraryChatRepository chatRepository;
    private final TaskExecutor taskExecutor;

    public PdfIngestionStreamService(
            PdfIngestionService ingestionService,
            LibraryChatRepository chatRepository,
            @Qualifier("ragTaskExecutor") TaskExecutor taskExecutor) {
        this.ingestionService = ingestionService;
        this.chatRepository = chatRepository;
        this.taskExecutor = taskExecutor;
    }

    public SseEmitter stream(UUID chatId, String fileName, byte[] pdfBytes) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        this.taskExecutor.execute(() -> run(chatId, fileName, pdfBytes, emitter));
        return emitter;
    }

    private void run(UUID chatId, String fileName, byte[] pdfBytes, SseEmitter emitter) {
        try {
            PdfIngestionResponse ingestion = this.ingestionService.ingest(
                    fileName,
                    pdfBytes,
                    event -> send(emitter, "progress", event)
            );
            LibraryChatDetail chat = this.chatRepository.attachDocuments(
                    chatId, List.of(ingestion.documentId())
            );
            send(emitter, "result", new ChatDocumentUploadResponse(ingestion, chat));
            emitter.complete();
        } catch (RuntimeException exception) {
            try {
                send(emitter, "failure", new PdfIngestionFailureEvent(safeMessage(exception)));
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
            throw new IllegalStateException("The client disconnected from the upload stream", exception);
        }
    }

    private String safeMessage(RuntimeException exception) {
        if (exception instanceof IllegalArgumentException && exception.getMessage() != null) {
            return exception.getMessage();
        }
        return "The document could not be indexed. Please try again.";
    }
}
