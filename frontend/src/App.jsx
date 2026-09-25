import { useEffect, useMemo, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import {
  attachChatDocuments,
  createChat,
  detachChatDocument,
  getChat,
  getChats,
  getDocuments,
  streamChatMessage,
  uploadChatDocument,
} from "./api.js";

const MAX_DOCUMENTS = 3;
const ACTIVE_CHAT_KEY = "library-ai.active-chat.v2";
const SIDEBAR_COLLAPSED_KEY = "library-ai.sidebar-collapsed.v1";
const MIN_PROGRESS_STAGE_MILLIS = 600;

function loadActiveChatId() {
  try {
    return localStorage.getItem(ACTIVE_CHAT_KEY);
  } catch {
    return null;
  }
}

function Icon({ name, size = 18 }) {
  const paths = {
    book: <><path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H20v15H6.5A2.5 2.5 0 0 0 4 20.5z"/><path d="M4 5.5v15A2.5 2.5 0 0 1 6.5 18H20"/></>,
    upload: <><path d="M12 16V4"/><path d="m7 9 5-5 5 5"/><path d="M5 20h14"/></>,
    file: <><path d="M6 2h8l4 4v16H6z"/><path d="M14 2v5h5"/><path d="M9 13h6M9 17h5"/></>,
    plus: <path d="M12 5v14M5 12h14"/>,
    send: <><path d="m4 4 17 8-17 8 3-8z"/><path d="M7 12h14"/></>,
    sparkle: <><path d="m12 3 1.2 3.8L17 8l-3.8 1.2L12 13l-1.2-3.8L7 8l3.8-1.2z"/><path d="m18 14 .8 2.2L21 17l-2.2.8L18 20l-.8-2.2L15 17l2.2-.8z"/></>,
    check: <path d="m5 12 4 4L19 6"/>,
    chevron: <path d="m9 18 6-6-6-6"/>,
    close: <path d="m6 6 12 12M18 6 6 18"/>,
    refresh: <><path d="M20 11a8 8 0 1 0-2.3 5.7"/><path d="M20 4v7h-7"/></>,
    chat: <><path d="M21 15a4 4 0 0 1-4 4H8l-5 3V7a4 4 0 0 1 4-4h10a4 4 0 0 1 4 4z"/><path d="M8 9h8M8 13h5"/></>,
    library: <><path d="M4 19.5V5a2 2 0 0 1 2-2h3v18H6a2 2 0 0 1-2-1.5z"/><path d="M9 5h5v16H9zM14 7l4-1 2 14-6 1z"/></>,
    panel: <><rect x="3" y="4" width="18" height="16" rx="2"/><path d="M9 4v16"/></>,
  };
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {paths[name]}
    </svg>
  );
}

function DocumentCard({ document, selected, disabled, onToggle }) {
  const ready = document.status === "READY";
  return (
    <button
      type="button"
      className={`document-card ${selected ? "selected" : ""}`}
      onClick={() => onToggle(document.documentId)}
      disabled={disabled || !ready}
      aria-pressed={selected}
    >
      <span className="document-icon"><Icon name="file" size={19} /></span>
      <span className="document-copy">
        <strong title={document.fileName}>{document.fileName}</strong>
        <span>{ready ? `${document.chunkCount} indexed chunks` : document.status.toLowerCase()}</span>
      </span>
      <span className={`document-check ${selected ? "checked" : ""}`}>
        {selected && <Icon name="check" size={14} />}
      </span>
    </button>
  );
}

function SourceList({ sources, focusRequest, sourceGroupId, onSourceSelect }) {
  const [showAll, setShowAll] = useState(false);
  const focusedRank = focusRequest?.rank;

  useEffect(() => {
    if (!focusedRank || !sources?.some((source) => source.rank === focusedRank)) return;
    if (!showAll && sources.findIndex((source) => source.rank === focusedRank) >= 2) {
      setShowAll(true);
    }
  }, [focusRequest, focusedRank, showAll, sourceGroupId, sources]);

  if (!sources?.length) return null;
  const hiddenSourceCount = Math.max(0, sources.length - 2);
  const visibleSources = showAll ? sources : sources.slice(0, 2);

  return (
    <div className="sources">
      <div className="sources-label">Sources used</div>
      {visibleSources.map((source) => {
        const metadata = source.metadata || {};
        const pages = Array.isArray(metadata.page_numbers)
          ? metadata.page_numbers.join(", ")
          : metadata.page_numbers || metadata.page_number || "—";
        return (
          <details
            className={`source-card ${focusedRank === source.rank ? "citation-target" : ""}`}
            id={`${sourceGroupId}-source-${source.rank}`}
            key={`${source.rank}-${metadata.document_id}-${metadata.chunk_index}`}
          >
            <summary onClick={() => onSourceSelect?.(source)}>
              <span className="source-rank">{source.rank}</span>
              <span className="source-title">
                <strong>{metadata.section_title || metadata.source_file_name || "Document source"}</strong>
                <small>{metadata.source_file_name || "Selected document"} · Page {pages}</small>
              </span>
              <span className="source-score">{Math.round(source.score * 100)}%</span>
              <Icon name="chevron" size={15} />
            </summary>
            <p>{source.text}</p>
          </details>
        );
      })}
      {hiddenSourceCount > 0 && (
        <button
          type="button"
          className="source-list-toggle"
          aria-expanded={showAll}
          onClick={() => setShowAll((current) => !current)}
        >
          <span>
            {showAll
              ? "Show fewer sources"
              : `Show ${hiddenSourceCount} more source${hiddenSourceCount === 1 ? "" : "s"}`}
          </span>
          <span className={`toggle-chevron ${showAll ? "expanded" : ""}`}>
            <Icon name="chevron" size={15} />
          </span>
        </button>
      )}
    </div>
  );
}

function MarkdownAnswer({ children, onCitationClick }) {
  const linkedCitations = children.replace(
    /\[Source\s+(\d+)((?:\s*,\s*Source\s+\d+)*)\](?!\()/gi,
    (_citation, firstRank, remainingRanks) => {
      const ranks = [
        firstRank,
        ...Array.from(remainingRanks.matchAll(/Source\s+(\d+)/gi), (match) => match[1]),
      ];
      return ranks.map((rank) => `[Source ${rank}](#source-${rank})`).join(", ");
    },
  );

  return (
    <ReactMarkdown
      remarkPlugins={[remarkGfm]}
      components={{
        a: ({ node: _node, href, children: linkChildren, ...props }) => {
          const citation = href?.match(/^#source-(\d+)$/);
          if (citation) {
            return (
              <button
                type="button"
                className="citation-link"
                onClick={() => onCitationClick(Number(citation[1]))}
                aria-label={`Open supporting source ${citation[1]}`}
              >
                {linkChildren}
              </button>
            );
          }
          return <a href={href} {...props} target="_blank" rel="noreferrer">{linkChildren}</a>;
        },
      }}
    >
      {linkedCitations}
    </ReactMarkdown>
  );
}

function GroundedResponse({ text, sources, usage, responseId, streaming = false, onSourceSelect }) {
  const [focusRequest, setFocusRequest] = useState(null);

  function focusSource(rank) {
    setFocusRequest({ rank, requestedAt: Date.now() });
    const source = sources?.find((item) => item.rank === rank);
    if (source) onSourceSelect?.(source);
  }

  return (
    <>
      <div className={`message-body ${streaming ? "streaming-answer" : ""}`}>
        <MarkdownAnswer onCitationClick={focusSource}>{text}</MarkdownAnswer>
        {streaming && <span className="streaming-cursor" aria-label="Answer is still being written" />}
      </div>
      <SourceList
        sources={sources}
        focusRequest={focusRequest}
        sourceGroupId={responseId}
        onSourceSelect={onSourceSelect}
      />
      {usage != null && <div className="token-usage">{usage.toLocaleString()} model tokens</div>}
    </>
  );
}

function SourcePreview({ source, onClose }) {
  if (!source) return null;
  const metadata = source.metadata || {};
  const pages = Array.isArray(metadata.page_numbers)
    ? metadata.page_numbers.join(", ")
    : metadata.page_numbers || metadata.page_number || "—";

  return (
    <aside className="evidence-panel" aria-label={`Source ${source.rank} evidence`}>
      <div className="evidence-header">
        <div>
          <span className="eyebrow">Evidence</span>
          <h2>Source {source.rank}</h2>
        </div>
        <button type="button" className="icon-button evidence-close" onClick={onClose} aria-label="Close evidence panel">
          <Icon name="close" size={16} />
        </button>
      </div>

      <div className="evidence-document">
        <span className="document-icon"><Icon name="file" size={18} /></span>
        <span>
          <strong>{metadata.source_file_name || "Selected document"}</strong>
          <small>Page {pages}</small>
        </span>
      </div>

      <div className="evidence-section">
        <span>Section</span>
        <strong>{metadata.section_title || "Document passage"}</strong>
      </div>

      <div className="evidence-passage">
        <span className="evidence-page-label">Retrieved passage</span>
        <p>{source.text}</p>
      </div>

      <div className="evidence-score">
        <span>Retrieval relevance</span>
        <strong>{Math.round(source.score * 100)}%</strong>
      </div>
    </aside>
  );
}

const RAG_PROGRESS_STEPS = [
  {
    stage: "UNDERSTANDING",
    label: "Understanding your question",
    shortLabel: "Understanding",
    completeLabel: "Understood",
  },
  {
    stage: "SEARCHING",
    label: "Searching selected documents",
    shortLabel: "Finding sources",
    completeLabel: "Sources found",
  },
  {
    stage: "GENERATING",
    label: "Creating your grounded answer",
    shortLabel: "Writing answer",
    completeLabel: "Answer created",
  },
];

function RagProgress({ progress, documentCount }) {
  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  const activeIndex = Math.max(
    0,
    RAG_PROGRESS_STEPS.findIndex((step) => step.stage === progress?.stage),
  );

  useEffect(() => {
    const startedAt = Date.now();
    const timer = window.setInterval(() => {
      setElapsedSeconds(Math.floor((Date.now() - startedAt) / 1000));
    }, 1000);
    return () => window.clearInterval(timer);
  }, []);

  const activeStep = RAG_PROGRESS_STEPS[activeIndex];
  const activeDetail = {
    UNDERSTANDING: "Creating a semantic search from your question",
    SEARCHING: `Comparing your question with passages across ${documentCount} document${documentCount === 1 ? "" : "s"}`,
    GENERATING: `Relevant passages found across ${documentCount} document${documentCount === 1 ? "" : "s"}`,
  }[activeStep.stage];

  return (
    <div className="rag-progress" role="status" aria-live="polite">
      <div className="progress-current">
        <span className="progress-spinner" aria-hidden="true" />
        <span className="progress-copy">
          <strong>{activeStep.label}</strong>
          <small>{activeDetail}</small>
        </span>
        <span className="progress-time" aria-label={`${elapsedSeconds} seconds elapsed`}>
          {elapsedSeconds}s
        </span>
      </div>

      <div className="progress-milestones" aria-label="Answer preparation progress">
        {RAG_PROGRESS_STEPS.map((step, index) => {
          const state = index < activeIndex ? "complete" : index === activeIndex ? "active" : "pending";
          return (
            <span className={`progress-milestone ${state}`} key={step.stage}>
              <span className="milestone-marker">
                {state === "complete" ? <Icon name="check" size={11} /> : <span />}
              </span>
              {state === "complete" ? step.completeLabel : step.shortLabel}
            </span>
          );
        })}
      </div>
    </div>
  );
}

function App() {
  const [chats, setChats] = useState([]);
  const [activeChatId, setActiveChatId] = useState(loadActiveChatId);
  const [activeChat, setActiveChat] = useState(null);
  const [documents, setDocuments] = useState([]);
  const [messages, setMessages] = useState([]);
  const [message, setMessage] = useState("");
  const [loadingWorkspace, setLoadingWorkspace] = useState(true);
  const [changingContext, setChangingContext] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [sending, setSending] = useState(false);
  const [progress, setProgress] = useState(null);
  const [streamedAnswer, setStreamedAnswer] = useState("");
  const [streamedSources, setStreamedSources] = useState([]);
  const [notice, setNotice] = useState(null);
  const [activeSource, setActiveSource] = useState(null);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(() => {
    try {
      return localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === "true";
    } catch {
      return false;
    }
  });
  const fileInputRef = useRef(null);
  const messagesRef = useRef(null);

  const selectedDocuments = activeChat?.documents || [];
  const selectedIds = useMemo(
    () => selectedDocuments.map((document) => document.documentId),
    [selectedDocuments],
  );

  function normalizeMessages(chat) {
    return (chat?.messages || []).map((item) => ({
      role: item.role,
      text: item.text,
      sources: item.sources || [],
      usage: item.totalTokens,
      messageId: item.messageId,
    }));
  }

  async function refreshChats() {
    const result = await getChats();
    setChats(result);
    return result;
  }

  async function openChat(chatId) {
    if (!chatId) return;
    setActiveSource(null);
    setNotice(null);
    try {
      const detail = await getChat(chatId);
      setActiveChatId(detail.chatId);
      setActiveChat(detail);
      setMessages(normalizeMessages(detail));
    } catch (error) {
      setNotice({ type: "error", text: error.message });
    }
  }

  async function bootstrapWorkspace() {
    setLoadingWorkspace(true);
    try {
      const [documentResult, chatResult] = await Promise.all([getDocuments(), getChats()]);
      setDocuments(documentResult);
      let availableChats = chatResult;
      if (availableChats.length === 0) {
        const created = await createChat();
        availableChats = [{
          chatId: created.chatId,
          title: created.title,
          contextVersion: created.contextVersion,
          documentCount: created.documents.length,
          messageCount: created.messages.length,
          updatedAt: created.updatedAt,
        }];
      }
      setChats(availableChats);
      const rememberedChatId = loadActiveChatId();
      const initialChatId = availableChats.some((chat) => chat.chatId === rememberedChatId)
        ? rememberedChatId
        : availableChats[0].chatId;
      await openChat(initialChatId);
    } catch (error) {
      setNotice({ type: "error", text: error.message });
    } finally {
      setLoadingWorkspace(false);
    }
  }

  useEffect(() => { bootstrapWorkspace(); }, []);
  useEffect(() => {
    const messageContainer = messagesRef.current;
    if (!messageContainer) return;
    messageContainer.scrollTo({
      top: messageContainer.scrollHeight,
      behavior: "smooth",
    });
  }, [messages, sending]);
  useEffect(() => {
    try {
      if (activeChatId) localStorage.setItem(ACTIVE_CHAT_KEY, activeChatId);
    } catch {
      // Remembering the last opened chat is optional.
    }
  }, [activeChatId]);
  useEffect(() => {
    try {
      localStorage.setItem(SIDEBAR_COLLAPSED_KEY, String(sidebarCollapsed));
    } catch {
      // Sidebar preference is optional when browser storage is unavailable.
    }
  }, [sidebarCollapsed]);

  async function toggleDocument(documentId) {
    if (!activeChatId || changingContext || sending) return;
    if (!selectedIds.includes(documentId) && selectedIds.length >= MAX_DOCUMENTS) {
      setNotice({ type: "error", text: "A chat can contain up to 3 documents." });
      return;
    }
    setChangingContext(true);
    setNotice(null);
    try {
      const detail = selectedIds.includes(documentId)
        ? await detachChatDocument(activeChatId, documentId)
        : await attachChatDocuments(activeChatId, [documentId]);
      setActiveChat(detail);
      setMessages(normalizeMessages(detail));
      await refreshChats();
      setNotice({
        type: "success",
        text: selectedIds.includes(documentId)
          ? "Document removed from this chat. Future questions use the remaining context."
          : "Document attached. Future questions can retrieve from it.",
      });
    } catch (error) {
      setNotice({ type: "error", text: error.message });
    } finally {
      setChangingContext(false);
    }
  }

  async function handleUpload(event) {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) return;
    if (file.type !== "application/pdf" && !file.name.toLowerCase().endsWith(".pdf")) {
      setNotice({ type: "error", text: "Please choose a PDF file." });
      return;
    }

    setUploading(true);
    setNotice({ type: "info", text: `Indexing ${file.name}…` });
    try {
      if (!activeChatId) throw new Error("Create a chat before uploading a document.");
      if (selectedIds.length >= MAX_DOCUMENTS) throw new Error("This chat already has 3 documents.");
      const result = await uploadChatDocument(activeChatId, file);
      setActiveChat(result.chat);
      setMessages(normalizeMessages(result.chat));
      setDocuments(await getDocuments());
      await refreshChats();
      setNotice({
        type: "success",
        text: result.ingestion.skippedAsDuplicate
          ? `${file.name} was already indexed. Existing document selected.`
          : `${file.name} is ready with ${result.ingestion.storedChunkCount} chunks.`,
      });
    } catch (error) {
      setNotice({ type: "error", text: error.message });
    } finally {
      setUploading(false);
    }
  }

  async function startNewChat() {
    if (sending) return;
    try {
      const created = await createChat();
      setChats((current) => [{
        chatId: created.chatId,
        title: created.title,
        contextVersion: created.contextVersion,
        documentCount: 0,
        messageCount: 0,
        updatedAt: created.updatedAt,
      }, ...current]);
      setActiveChatId(created.chatId);
      setActiveChat(created);
      setMessages([]);
      setMessage("");
      setNotice(null);
      setActiveSource(null);
    } catch (error) {
      setNotice({ type: "error", text: error.message });
    }
  }

  async function submitMessage(event) {
    event.preventDefault();
    const cleanMessage = message.trim();
    if (!cleanMessage || sending) return;
    if (selectedIds.length === 0) {
      setNotice({ type: "error", text: "Select at least one document before asking a question." });
      return;
    }

    setNotice(null);
    setMessages((current) => [...current, { role: "user", text: cleanMessage }]);
    setMessage("");
    setSending(true);
    setProgress({ stage: "UNDERSTANDING", message: "Understanding your question" });
    setStreamedAnswer("");
    setStreamedSources([]);
    try {
      const result = await streamChatMessage(
        activeChatId,
        {
          message: cleanMessage,
          topK: 5,
        },
        {
          onProgress: async (progressEvent) => {
            setProgress(progressEvent);
            // Fast stages still remain visible long enough to be understood.
            await new Promise((resolve) => window.setTimeout(resolve, MIN_PROGRESS_STAGE_MILLIS));
          },
          onSources: (sourceEvent) => setStreamedSources(sourceEvent.sources || []),
          onToken: (text) => setStreamedAnswer((current) => current + text),
        },
      );
      setMessages((current) => [...current, {
        role: "assistant",
        text: result.answer,
        sources: result.sources,
        usage: result.totalTokens,
      }]);
      const [detail] = await Promise.all([getChat(activeChatId), refreshChats()]);
      setActiveChat(detail);
      setMessages(normalizeMessages(detail));
    } catch (error) {
      setMessages((current) => [...current, {
        role: "error",
        text: `I couldn't complete that request. ${error.message}`,
      }]);
    } finally {
      setSending(false);
      setProgress(null);
      setStreamedAnswer("");
      setStreamedSources([]);
    }
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark"><Icon name="book" size={20} /></span>
          <span>Library <strong>AI</strong></span>
        </div>
        <div className="workspace-title">
          <strong>{activeChat?.title || "Opening your chats…"}</strong>
          <span>{selectedIds.length
            ? `${selectedIds.length} attached document${selectedIds.length === 1 ? "" : "s"} · History saved`
            : "Attach documents to this chat"}</span>
        </div>
        <button className="new-chat-button" type="button" onClick={startNewChat}>
          <Icon name="plus" size={17} /> New chat
        </button>
      </header>

      <div className={`workspace ${sidebarCollapsed ? "sidebar-collapsed" : ""} ${activeSource ? "evidence-open" : ""}`}>
        <nav className="navigation-rail" aria-label="Workspace navigation">
          <button type="button" className="rail-button selected" aria-label="Document chat">
            <Icon name="chat" size={18} />
          </button>
          <button
            type="button"
            className={`rail-button ${sidebarCollapsed ? "" : "selected"}`}
            aria-label={sidebarCollapsed ? "Expand document library" : "Collapse document library"}
            aria-expanded={!sidebarCollapsed}
            onClick={() => setSidebarCollapsed((current) => !current)}
          >
            <Icon name="library" size={18} />
          </button>
          <button
            type="button"
            className="rail-button rail-toggle"
            aria-label={sidebarCollapsed ? "Expand sidebar" : "Minimize sidebar"}
            onClick={() => setSidebarCollapsed((current) => !current)}
          >
            <span className={sidebarCollapsed ? "panel-expand-icon" : ""}><Icon name="panel" size={17} /></span>
          </button>
        </nav>

        {!sidebarCollapsed && <aside className="sidebar">
          <div className="sidebar-heading">
            <div>
              <span className="eyebrow">Your workspace</span>
              <h2>Chats</h2>
            </div>
            <div className="sidebar-heading-actions">
              <button type="button" className="icon-button" onClick={() => setSidebarCollapsed(true)} aria-label="Minimize sidebar">
                <Icon name="chevron" size={15} />
              </button>
            </div>
          </div>
          <p className="sidebar-description">Each chat keeps its own documents, messages, and cited evidence.</p>

          <div className="chat-list" aria-label="Saved chats">
            {loadingWorkspace && <div className="list-state">Loading your chats…</div>}
            {chats.map((chat) => (
              <button
                type="button"
                className={`chat-list-item ${chat.chatId === activeChatId ? "selected" : ""}`}
                key={chat.chatId}
                disabled={sending}
                onClick={() => openChat(chat.chatId)}
              >
                <span className="chat-list-icon"><Icon name="chat" size={15} /></span>
                <span className="chat-list-copy">
                  <strong>{chat.title}</strong>
                  <small>{chat.documentCount} docs · {chat.messageCount} messages</small>
                </span>
              </button>
            ))}
          </div>

          <div className="document-list-heading context-heading">
            <span>Documents in this chat</span>
            <span className="selection-count">{selectedIds.length}/{MAX_DOCUMENTS}</span>
          </div>

          <input ref={fileInputRef} type="file" accept="application/pdf,.pdf" hidden onChange={handleUpload} />
          <button className="upload-button" type="button" disabled={uploading || !activeChatId || selectedIds.length >= MAX_DOCUMENTS} onClick={() => fileInputRef.current?.click()}>
            <Icon name="upload" size={18} />
            <span>{uploading ? "Indexing document…" : "Upload a PDF"}</span>
          </button>

          <div className="document-list-heading indexed-heading">
            <span>Indexed documents</span>
            <button type="button" className="icon-button" onClick={bootstrapWorkspace} aria-label="Refresh documents and chats">
              <Icon name="refresh" size={15} />
            </button>
          </div>

          <div className="document-list">
            {loadingWorkspace && <div className="list-state">Loading your library…</div>}
            {!loadingWorkspace && documents.length === 0 && (
              <div className="empty-library"><Icon name="file" size={24} /><span>Upload your first PDF to begin.</span></div>
            )}
            {documents.map((document) => (
              <DocumentCard
                key={document.documentId}
                document={document}
                selected={selectedIds.includes(document.documentId)}
                disabled={changingContext || sending}
                onToggle={toggleDocument}
              />
            ))}
          </div>

          <div className="context-lock">
            Only embeddings from documents attached to this chat are searched. Changing documents starts a new context version without deleting older messages.
          </div>
        </aside>}

        <main className="chat-panel">
          <div className="chat-header">
            <div>
              <span className="eyebrow">Document chat</span>
              <h1>{activeChat?.title || (selectedDocuments.length ? "Ask your library" : "Select your sources")}</h1>
            </div>
            <div className="context-pills">
              {selectedDocuments.map((document) => (
                <span className="context-pill" key={document.documentId} title={document.fileName}>
                  <Icon name="file" size={13} /> {document.fileName}
                </span>
              ))}
            </div>
          </div>

          {notice && (
            <div className={`notice ${notice.type}`}>
              <span>{notice.text}</span>
              <button type="button" onClick={() => setNotice(null)} aria-label="Dismiss"><Icon name="close" size={14} /></button>
            </div>
          )}

          <div className="messages" aria-live="polite" ref={messagesRef}>
            {!loadingWorkspace && messages.length === 0 && (
              <section className="welcome-state">
                <div className="welcome-icon"><Icon name="sparkle" size={27} /></div>
                <h2>Answers grounded in your documents</h2>
                <p>Attach one to three PDFs to this chat, then ask a question. This chat and its source-backed history remain available when you return.</p>
                <div className="suggestions">
                  {["Summarize the key ideas", "Compare the selected documents", "What should I learn first?"].map((text) => (
                    <button type="button" key={text} onClick={() => setMessage(text)}>{text}</button>
                  ))}
                </div>
              </section>
            )}

            {messages.map((item, index) => (
              <article className={`message ${item.role}`} key={item.messageId || `${item.role}-${index}`}>
                <div className="message-label">{item.role === "user" ? "You" : item.role === "assistant" ? "Library AI" : "Request error"}</div>
                {item.role === "assistant"
                  ? (
                    <GroundedResponse
                      text={item.text}
                      sources={item.sources}
                      usage={item.usage}
                      responseId={`response-${index}`}
                      onSourceSelect={setActiveSource}
                    />
                  )
                  : <div className="message-body">{item.text}</div>}
              </article>
            ))}

            {sending && (
              <article className="message assistant loading-message">
                <div className="message-label">Library AI</div>
                {streamedAnswer
                  ? (
                    <GroundedResponse
                      text={streamedAnswer}
                      sources={streamedSources}
                      responseId="streaming-response"
                      streaming
                      onSourceSelect={setActiveSource}
                    />
                  )
                  : <RagProgress progress={progress} documentCount={selectedIds.length} />}
              </article>
            )}
          </div>

          <form className="composer" onSubmit={submitMessage}>
            <textarea
              value={message}
              onChange={(event) => setMessage(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Enter" && !event.shiftKey) {
                  event.preventDefault();
                  submitMessage(event);
                }
              }}
              placeholder={selectedIds.length ? "Ask a question about this chat's documents…" : "Attach at least one document to begin…"}
              rows="1"
              disabled={sending}
            />
            <button className="send-button" type="submit" disabled={sending || !message.trim() || selectedIds.length === 0} aria-label="Send question">
              <Icon name="send" size={18} />
            </button>
            <div className="composer-meta">
              <span>{selectedIds.length ? `${selectedIds.length} document${selectedIds.length > 1 ? "s" : ""} in context` : "No context selected"}</span>
              <span>Enter to send · Shift + Enter for a new line</span>
            </div>
          </form>
        </main>

        <SourcePreview source={activeSource} onClose={() => setActiveSource(null)} />
      </div>
    </div>
  );
}

export default App;
