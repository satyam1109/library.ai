package com.libraryai.rag;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Chat-centered API. Document scope is resolved by chatId on the server. */
@RestController
@RequestMapping("/api/chats")
public class LibraryChatController {

    private final LibraryChatRepository chatRepository;
    private final PdfIngestionService ingestionService;
    private final RagChatService ragChatService;
    private final RagChatStreamService streamService;
    private final PdfIngestionStreamService ingestionStreamService;

    public LibraryChatController(
            LibraryChatRepository chatRepository,
            PdfIngestionService ingestionService,
            RagChatService ragChatService,
            RagChatStreamService streamService,
            PdfIngestionStreamService ingestionStreamService) {
        this.chatRepository = chatRepository;
        this.ingestionService = ingestionService;
        this.ragChatService = ragChatService;
        this.streamService = streamService;
        this.ingestionStreamService = ingestionStreamService;
    }

    @GetMapping
    public List<LibraryChatSummary> chats() {
        return this.chatRepository.findAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LibraryChatDetail create(@RequestBody(required = false) CreateLibraryChatRequest request) {
        return this.chatRepository.create(request == null ? null : request.title());
    }

    @GetMapping("/{chatId}")
    public LibraryChatDetail chat(@PathVariable UUID chatId) {
        return this.chatRepository.findDetail(chatId);
    }

    @PatchMapping("/{chatId}")
    public LibraryChatDetail rename(
            @PathVariable UUID chatId,
            @RequestBody RenameLibraryChatRequest request) {
        return this.chatRepository.rename(chatId, request.title());
    }

    @PostMapping("/{chatId}/documents")
    public LibraryChatDetail attachDocuments(
            @PathVariable UUID chatId,
            @RequestBody AttachChatDocumentsRequest request) {
        return this.chatRepository.attachDocuments(chatId, request.documentIds());
    }

    @DeleteMapping("/{chatId}/documents/{documentId}")
    public LibraryChatDetail detachDocument(
            @PathVariable UUID chatId,
            @PathVariable UUID documentId) {
        return this.chatRepository.detachDocument(chatId, documentId);
    }

    @PostMapping(
            value = "/{chatId}/documents/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChatDocumentUploadResponse uploadAndAttach(
            @PathVariable UUID chatId,
            @RequestPart("file") MultipartFile file) {
        validatePdf(file);
        try {
            PdfIngestionResponse ingestion = this.ingestionService.ingest(
                    file.getOriginalFilename(), file.getBytes()
            );
            LibraryChatDetail chat = this.chatRepository.attachDocuments(
                    chatId, List.of(ingestion.documentId())
            );
            return new ChatDocumentUploadResponse(ingestion, chat);
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Could not read uploaded PDF", exception
            );
        }
    }

    @PostMapping(
            value = "/{chatId}/documents/upload/stream",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamUploadAndAttach(
            @PathVariable UUID chatId,
            @RequestPart("file") MultipartFile file) {
        validatePdf(file);
        try {
            return this.ingestionStreamService.stream(
                    chatId, file.getOriginalFilename(), file.getBytes()
            );
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Could not read uploaded PDF", exception
            );
        }
    }

    @PostMapping("/{chatId}/messages")
    public RagChatResponse message(
            @PathVariable UUID chatId,
            @RequestBody ChatMessageRequest request) {
        return this.ragChatService.chat(chatId, request);
    }

    @PostMapping(
            value = "/{chatId}/messages/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessage(
            @PathVariable UUID chatId,
            @RequestBody ChatMessageRequest request) {
        return this.streamService.stream(chatId, request);
    }

    private void validatePdf(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PDF file must not be empty");
        }
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PDF files are supported");
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String invalidRequest(IllegalArgumentException exception) {
        return exception.getMessage();
    }
}
