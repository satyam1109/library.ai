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

export async function sendRagMessage(payload) {
  return parseResponse(
    await fetch("/api/rag/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    }),
  );
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
export async function streamRagMessage(payload, handlers = {}) {
  const response = await fetch("/api/rag/chat/stream", {
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
