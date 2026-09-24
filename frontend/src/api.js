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
