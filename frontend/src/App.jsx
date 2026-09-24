import { useEffect, useMemo, useRef, useState } from "react";
import { getDocuments, sendRagMessage, uploadDocument } from "./api.js";

const MAX_DOCUMENTS = 3;
const ACTIVE_CHAT_SESSION_KEY = "library-ai.active-chat.v1";

function emptyChatSession() {
  return {
    conversationId: crypto.randomUUID(),
    selectedIds: [],
    messages: [],
  };
}

function loadChatSession() {
  try {
    const stored = JSON.parse(sessionStorage.getItem(ACTIVE_CHAT_SESSION_KEY));
    if (
      typeof stored?.conversationId !== "string"
      || !Array.isArray(stored?.selectedIds)
      || !Array.isArray(stored?.messages)
    ) {
      return emptyChatSession();
    }
    return {
      conversationId: stored.conversationId,
      selectedIds: stored.selectedIds.filter((id) => typeof id === "string").slice(0, MAX_DOCUMENTS),
      messages: stored.messages.filter((item) =>
        item && ["user", "assistant", "error"].includes(item.role) && typeof item.text === "string"
      ),
    };
  } catch {
    return emptyChatSession();
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

function SourceList({ sources }) {
  if (!sources?.length) return null;
  return (
    <div className="sources">
      <div className="sources-label">Sources used</div>
      {sources.map((source) => {
        const metadata = source.metadata || {};
        const pages = Array.isArray(metadata.page_numbers)
          ? metadata.page_numbers.join(", ")
          : metadata.page_numbers || metadata.page_number || "—";
        return (
          <details className="source-card" key={`${source.rank}-${metadata.document_id}-${metadata.chunk_index}`}>
            <summary>
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
    </div>
  );
}

function App() {
  const [initialChat] = useState(loadChatSession);
  const [documents, setDocuments] = useState([]);
  const [selectedIds, setSelectedIds] = useState(initialChat.selectedIds);
  const [messages, setMessages] = useState(initialChat.messages);
  const [conversationId, setConversationId] = useState(initialChat.conversationId);
  const [message, setMessage] = useState("");
  const [loadingDocuments, setLoadingDocuments] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [sending, setSending] = useState(false);
  const [notice, setNotice] = useState(null);
  const fileInputRef = useRef(null);
  const endRef = useRef(null);

  const selectedDocuments = useMemo(
    () => selectedIds.map((id) => documents.find((document) => document.documentId === id)).filter(Boolean),
    [documents, selectedIds],
  );
  const chatStarted = messages.length > 0;

  async function loadDocuments() {
    setLoadingDocuments(true);
    try {
      const result = await getDocuments();
      setDocuments(result);
      setSelectedIds((current) => current.filter((id) => result.some((item) => item.documentId === id)));
    } catch (error) {
      setNotice({ type: "error", text: error.message });
    } finally {
      setLoadingDocuments(false);
    }
  }

  useEffect(() => { loadDocuments(); }, []);
  useEffect(() => { endRef.current?.scrollIntoView({ behavior: "smooth" }); }, [messages, sending]);
  useEffect(() => {
    try {
      sessionStorage.setItem(ACTIVE_CHAT_SESSION_KEY, JSON.stringify({
        conversationId,
        selectedIds,
        messages,
      }));
    } catch {
      // The chat still works if a browser blocks session storage.
    }
  }, [conversationId, selectedIds, messages]);

  function toggleDocument(documentId) {
    if (chatStarted) {
      setNotice({ type: "info", text: "Start a new chat before changing the document context." });
      return;
    }
    setNotice(null);
    setSelectedIds((current) => {
      if (current.includes(documentId)) return current.filter((id) => id !== documentId);
      if (current.length >= MAX_DOCUMENTS) {
        setNotice({ type: "error", text: "You can select up to 3 documents in one chat." });
        return current;
      }
      return [...current, documentId];
    });
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
      const result = await uploadDocument(file);
      await loadDocuments();
      if (!chatStarted && selectedIds.length < MAX_DOCUMENTS) {
        setSelectedIds((current) => current.includes(result.documentId) ? current : [...current, result.documentId]);
      }
      setNotice({
        type: "success",
        text: result.skippedAsDuplicate
          ? `${file.name} was already indexed. Existing document selected.`
          : `${file.name} is ready with ${result.storedChunkCount} chunks.`,
      });
    } catch (error) {
      setNotice({ type: "error", text: error.message });
    } finally {
      setUploading(false);
    }
  }

  function startNewChat() {
    setMessages([]);
    setMessage("");
    setConversationId(crypto.randomUUID());
    setNotice(null);
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
    try {
      const result = await sendRagMessage({
        conversationId,
        documentIds: selectedIds,
        message: cleanMessage,
        topK: 5,
      });
      setMessages((current) => [...current, {
        role: "assistant",
        text: result.answer,
        sources: result.sources,
        usage: result.totalTokens,
      }]);
    } catch (error) {
      setMessages((current) => [...current, {
        role: "error",
        text: `I couldn't complete that request. ${error.message}`,
      }]);
    } finally {
      setSending(false);
    }
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark"><Icon name="book" size={20} /></span>
          <span>Library <strong>AI</strong></span>
        </div>
        <button className="new-chat-button" type="button" onClick={startNewChat}>
          <Icon name="plus" size={17} /> New chat
        </button>
      </header>

      <div className="workspace">
        <aside className="sidebar">
          <div className="sidebar-heading">
            <div>
              <span className="eyebrow">Your library</span>
              <h2>Choose context</h2>
            </div>
            <span className="selection-count">{selectedIds.length}/{MAX_DOCUMENTS}</span>
          </div>
          <p className="sidebar-description">Select up to three documents. Every answer stays grounded in this context.</p>

          <input ref={fileInputRef} type="file" accept="application/pdf,.pdf" hidden onChange={handleUpload} />
          <button className="upload-button" type="button" disabled={uploading} onClick={() => fileInputRef.current?.click()}>
            <Icon name="upload" size={18} />
            <span>{uploading ? "Indexing document…" : "Upload a PDF"}</span>
          </button>

          <div className="document-list-heading">
            <span>Indexed documents</span>
            <button type="button" className="icon-button" onClick={loadDocuments} aria-label="Refresh documents">
              <Icon name="refresh" size={15} />
            </button>
          </div>

          <div className="document-list">
            {loadingDocuments && <div className="list-state">Loading your library…</div>}
            {!loadingDocuments && documents.length === 0 && (
              <div className="empty-library"><Icon name="file" size={24} /><span>Upload your first PDF to begin.</span></div>
            )}
            {documents.map((document) => (
              <DocumentCard
                key={document.documentId}
                document={document}
                selected={selectedIds.includes(document.documentId)}
                disabled={chatStarted}
                onToggle={toggleDocument}
              />
            ))}
          </div>

          {chatStarted && (
            <div className="context-lock">
              Context is locked for this chat. History is retained in this browser tab.
              Start a new chat to change the context.
            </div>
          )}
        </aside>

        <main className="chat-panel">
          <div className="chat-header">
            <div>
              <span className="eyebrow">Document chat</span>
              <h1>{selectedDocuments.length ? "Ask your library" : "Select your sources"}</h1>
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

          <div className="messages" aria-live="polite">
            {messages.length === 0 && (
              <section className="welcome-state">
                <div className="welcome-icon"><Icon name="sparkle" size={27} /></div>
                <h2>Answers grounded in your documents</h2>
                <p>Select one to three PDFs, then ask a question. Library AI retrieves the most relevant passages before answering.</p>
                <div className="suggestions">
                  {["Summarize the key ideas", "Compare the selected documents", "What should I learn first?"].map((text) => (
                    <button type="button" key={text} onClick={() => setMessage(text)}>{text}</button>
                  ))}
                </div>
              </section>
            )}

            {messages.map((item, index) => (
              <article className={`message ${item.role}`} key={`${item.role}-${index}`}>
                <div className="message-label">{item.role === "user" ? "You" : item.role === "assistant" ? "Library AI" : "Request error"}</div>
                <div className="message-body">{item.text}</div>
                {item.role === "assistant" && (
                  <>
                    <SourceList sources={item.sources} />
                    {item.usage != null && <div className="token-usage">{item.usage.toLocaleString()} model tokens</div>}
                  </>
                )}
              </article>
            ))}

            {sending && (
              <article className="message assistant loading-message">
                <div className="message-label">Library AI</div>
                <div className="thinking"><span /><span /><span /> Searching selected documents</div>
              </article>
            )}
            <div ref={endRef} />
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
              placeholder={selectedIds.length ? "Ask a question about the selected documents…" : "Select at least one document to begin…"}
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
      </div>
    </div>
  );
}

export default App;
