async function parseResponse(response) {
  if (response.ok) {
    if (response.status === 204) return null;
    return response.json();
  }

  const raw = await response.text();
  let message = raw || `Request failed with status ${response.status}`;
  try {
    const parsed = JSON.parse(raw);
    message = parsed.detail || parsed.message || parsed.error || message;
  } catch {
    // The backend intentionally returns plain text for validation errors.
  }
  throw new Error(message);
}

export async function getDocuments() {
  return parseResponse(await fetch("/api/documents"));
}

export async function uploadDocument(file) {
  const body = new FormData();
  body.append("file", file);
  return parseResponse(
    await fetch("/api/documents/ingest", {
      method: "POST",
      body,
    }),
  );
}

export async function getChats() {
  return parseResponse(await fetch("/api/chats"));
}

export async function createChat(title) {
  return parseResponse(
    await fetch("/api/chats", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ title }),
    }),
  );
}

export async function getChat(chatId) {
  return parseResponse(await fetch(`/api/chats/${chatId}`));
}

export async function renameChat(chatId, title) {
  return parseResponse(
    await fetch(`/api/chats/${chatId}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ title }),
    }),
  );
}

export async function attachChatDocuments(chatId, documentIds) {
  return parseResponse(
    await fetch(`/api/chats/${chatId}/documents`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ documentIds }),
    }),
  );
}

export async function detachChatDocument(chatId, documentId) {
  return parseResponse(
    await fetch(`/api/chats/${chatId}/documents/${documentId}`, { method: "DELETE" }),
  );
}

export async function uploadChatDocument(chatId, file) {
  const body = new FormData();
  body.append("file", file);
  return parseResponse(
    await fetch(`/api/chats/${chatId}/documents/upload`, {
      method: "POST",
      body,
    }),
  );
}

export async function streamChatDocument(chatId, file, handlers = {}) {
  const body = new FormData();
  body.append("file", file);
  const response = await fetch(`/api/chats/${chatId}/documents/upload/stream`, {
    method: "POST",
    headers: { "Accept": "text/event-stream" },
    body,
  });
  if (!response.ok) return parseResponse(response);
  if (!response.body) throw new Error("This browser cannot read streaming responses.");

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let result = null;

  async function handleEvent(block) {
    const decoded = decodeServerEvent(block);
    if (!decoded) return;
    if (decoded.event === "progress") handlers.onProgress?.(decoded.data);
    if (decoded.event === "result") result = decoded.data;
    if (decoded.event === "failure") {
      throw new Error(decoded.data.message || "The document could not be indexed.");
    }
  }

  while (true) {
    const { done, value } = await reader.read();
    buffer += decoder.decode(value || new Uint8Array(), { stream: !done });
    const blocks = buffer.split(/\r?\n\r?\n/);
    buffer = blocks.pop() || "";
    for (const block of blocks) await handleEvent(block);
    if (done) break;
  }
  if (buffer.trim()) await handleEvent(buffer);
  if (!result) throw new Error("The upload stream ended before a result was returned.");
  return result;
}

function decodeServerEvent(block) {
  let event = "message";
  const data = [];
  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith("event:")) event = line.slice(6).trim();
    if (line.startsWith("data:")) data.push(line.slice(5).trimStart());
  }
  if (data.length === 0) return null;
  return { event, data: JSON.parse(data.join("\n")) };
}

/**
 * Uses fetch instead of EventSource because this stream begins with a POST body.
 * The response still follows the standard text/event-stream wire format.
 */
export async function streamChatMessage(chatId, payload, handlers = {}) {
  const response = await fetch(`/api/chats/${chatId}/messages/stream`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Accept": "text/event-stream",
    },
    body: JSON.stringify(payload),
  });
  if (!response.ok) return parseResponse(response);
  if (!response.body) throw new Error("This browser cannot read streaming responses.");

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let result = null;

  async function handleEvent(block) {
    const decoded = decodeServerEvent(block);
    if (!decoded) return;
    if (decoded.event === "progress") await handlers.onProgress?.(decoded.data);
    if (decoded.event === "sources") handlers.onSources?.(decoded.data);
    if (decoded.event === "token") handlers.onToken?.(decoded.data.text);
    if (decoded.event === "result") result = decoded.data;
    if (decoded.event === "failure") {
      throw new Error(decoded.data.message || "The request could not be completed.");
    }
  }

  while (true) {
    const { done, value } = await reader.read();
    buffer += decoder.decode(value || new Uint8Array(), { stream: !done });
    const blocks = buffer.split(/\r?\n\r?\n/);
    buffer = blocks.pop() || "";
    for (const block of blocks) await handleEvent(block);
    if (done) break;
  }
  if (buffer.trim()) await handleEvent(buffer);
  if (!result) throw new Error("The answer stream ended before a result was returned.");
  return result;
}
