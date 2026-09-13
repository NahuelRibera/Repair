"use client";

import { useParams, useSearchParams } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { vehicleTitle } from "@/lib/types";
import type { ChatTurnResult, EvidenceCard, Message, RagRunDebug, SessionDetail } from "@/lib/types";
import { AnswerCard } from "@/components/AnswerCard";
import { Composer } from "@/components/Composer";
import { EvidenceDrawer } from "@/components/EvidenceDrawer";

interface TurnExtras {
  evidence: EvidenceCard[];
  debug: RagRunDebug;
}

export default function ConversationPage() {
  const params = useParams<{ sessionId: string }>();
  const searchParams = useSearchParams();
  const sessionId = Number(params.sessionId);
  const prefill = searchParams.get("prefill") ?? undefined;

  // Keying by sessionId forces a full remount (and therefore fresh state)
  // whenever the user switches conversations, instead of an effect that
  // manually resets half a dozen state variables and risks leaving a
  // stale one behind.
  return <ConversationView key={sessionId} sessionId={sessionId} prefill={prefill} />;
}

function ConversationView({ sessionId, prefill }: { sessionId: number; prefill?: string }) {
  const [detail, setDetail] = useState<SessionDetail | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const [sendError, setSendError] = useState<string | null>(null);
  const [turnExtras, setTurnExtras] = useState<Record<number, TurnExtras>>({});
  const [drawerFor, setDrawerFor] = useState<number | null>(null);
  const autoSentRef = useRef(false);
  const bottomRef = useRef<HTMLDivElement>(null);
  const scrollContainerRef = useRef<HTMLDivElement>(null);
  // Starts true so the first render of an existing conversation lands at
  // the bottom (the most recent message), matching normal chat UX.
  const isNearBottomRef = useRef(true);
  const requestGuard = useRef(0);

  const NEAR_BOTTOM_THRESHOLD_PX = 150;

  function handleMessagesScroll() {
    const el = scrollContainerRef.current;
    if (!el) return;
    const distanceFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
    isNearBottomRef.current = distanceFromBottom < NEAR_BOTTOM_THRESHOLD_PX;
  }

  useEffect(() => {
    const id = ++requestGuard.current;
    api
      .get<SessionDetail>(`/api/sessions/${sessionId}`)
      .then((d) => {
        if (requestGuard.current === id) setDetail(d);
      })
      .catch((e: unknown) => {
        if (requestGuard.current === id) {
          setLoadError(e instanceof ApiError ? e.message : "Failed to load conversation");
        }
      });
  }, [sessionId]);

  useEffect(() => {
    // Follows new content (an assistant reply arriving, the "Thinking…"
    // indicator appearing) only if the reader was already near the
    // bottom — reading older messages must never get yanked back down.
    // Sending a message forces isNearBottomRef true beforehand (see
    // sendMessage), so the user's own outgoing message always scrolls
    // into view regardless of prior scroll position.
    //
    // Uses "auto" (instant), not "smooth": smooth scrollIntoView proved
    // unreliable (silently a no-op in some environments, including this
    // project's own browser-automation tooling) — a chat app scrolling
    // reliably every time matters more than an animation.
    if (isNearBottomRef.current) {
      bottomRef.current?.scrollIntoView({ behavior: "auto" });
    }
  }, [detail?.messages.length, sending]);

  async function sendMessage(text: string) {
    if (!detail) return;
    const currentSessionId = sessionId;
    setSending(true);
    setSendError(null);
    // Sending your own message always scrolls it into view, regardless of
    // where you were reading — this is the one case that overrides the
    // "only follow if already near the bottom" rule below.
    isNearBottomRef.current = true;
    // Optimistic user bubble.
    setDetail((prev) =>
      prev
        ? {
            ...prev,
            messages: [
              ...prev.messages,
              { id: -Date.now(), role: "user", content: text, structuredResponseJson: null, createdAt: new Date().toISOString() },
            ],
          }
        : prev
    );
    try {
      const result = await api.post<ChatTurnResult>(`/api/sessions/${currentSessionId}/messages`, { content: text });
      if (currentSessionId !== sessionId) return; // user navigated away before this resolved
      setTurnExtras((prev) => ({ ...prev, [result.messageId]: { evidence: result.evidence, debug: result.debug } }));
      const fresh = await api.get<SessionDetail>(`/api/sessions/${currentSessionId}`);
      if (currentSessionId === sessionId) setDetail(fresh);
    } catch (e) {
      if (currentSessionId === sessionId) {
        setSendError(e instanceof ApiError ? e.message : "Something went wrong sending your message.");
      }
    } finally {
      if (currentSessionId === sessionId) setSending(false);
    }
  }

  useEffect(() => {
    if (prefill && detail && !autoSentRef.current && detail.messages.length === 0) {
      autoSentRef.current = true;
      sendMessage(prefill);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [prefill, detail]);

  if (loadError) {
    return (
      <div className="flex-1 flex items-center justify-center p-6">
        <div className="text-center">
          <p className="font-medium mb-1">Couldn&apos;t load this conversation</p>
          <p className="text-sm text-muted">{loadError}</p>
        </div>
      </div>
    );
  }

  if (!detail) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <p className="text-sm text-muted">Loading conversation…</p>
      </div>
    );
  }

  const activeExtras = drawerFor != null ? turnExtras[drawerFor] : null;

  return (
    <div className="flex-1 flex flex-col min-h-0">
      <header className="border-b border-border px-4 sm:px-6 py-3 flex items-center gap-3 shrink-0">
        <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-navy text-white text-xs font-semibold shrink-0">
          {detail.session.manufacturerName.slice(0, 2).toUpperCase()}
        </div>
        <div className="min-w-0">
          <p className="text-sm font-semibold truncate">
            {vehicleTitle(detail.session.manufacturerName, detail.session.modelName)}
          </p>
          <p className="text-xs text-muted truncate">{detail.session.variantName}</p>
        </div>
      </header>

      <div
        ref={scrollContainerRef}
        onScroll={handleMessagesScroll}
        className="flex-1 min-h-0 overflow-y-auto overscroll-contain px-4 sm:px-6 py-6"
      >
        <div className="mx-auto max-w-3xl space-y-6">
          {detail.messages.length === 0 && !sending && (
            <p className="text-sm text-muted text-center py-10">
              Describe the symptom you&apos;re seeing and I&apos;ll help narrow it down.
            </p>
          )}
          {detail.messages.map((message) => (
            <MessageBubble
              key={message.id}
              message={message}
              extras={turnExtras[message.id]}
              onOpenEvidence={() => setDrawerFor(message.id)}
              onFollowUpClick={(q) => sendMessage(q)}
            />
          ))}
          {sending && (
            <div className="flex items-center gap-2 text-sm text-muted">
              <span className="flex h-2 w-2 rounded-full bg-accent animate-pulse" />
              Thinking…
            </div>
          )}
          {sendError && (
            <div className="rounded-lg bg-red-50 border border-red-200 px-4 py-3 text-sm text-red-700 flex items-center justify-between gap-3">
              <span>{sendError}</span>
            </div>
          )}
          {/* A genuinely zero-height sentinel makes scrollIntoView a no-op
              in some browsers — h-px gives it real, if tiny, layout. */}
          <div ref={bottomRef} className="h-px" />
        </div>
      </div>

      <Composer disabled={sending} initialValue={prefill} onSend={sendMessage} />

      {drawerFor != null && (
        <EvidenceDrawer
          evidence={activeExtras?.evidence ?? []}
          debug={activeExtras?.debug ?? null}
          onClose={() => setDrawerFor(null)}
        />
      )}
    </div>
  );
}

function MessageBubble({
  message,
  extras,
  onOpenEvidence,
  onFollowUpClick,
}: {
  message: Message;
  extras?: TurnExtras;
  onOpenEvidence: () => void;
  onFollowUpClick: (q: string) => void;
}) {
  if (message.role === "user") {
    return (
      <div className="flex justify-end">
        <div className="max-w-[80%] rounded-2xl rounded-br-sm bg-accent text-white px-4 py-2.5 text-sm">
          {message.content}
        </div>
      </div>
    );
  }

  const parsed = message.structuredResponseJson ? safeParse(message.structuredResponseJson) : null;

  return (
    <div className="flex gap-3">
      <div className="flex h-8 w-8 items-center justify-center rounded-full bg-navy text-white shrink-0 mt-0.5">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none">
          <path
            d="M21.7 16.3l-4-4a5 5 0 0 0-6.2-6.2L8.4 9.2 4.9 5.7 2.3 8.3l3.5 3.5-3.1 3.1a5 5 0 0 0 6.2 6.2l4-4 4 4 4.8-4.8zM8 20a2 2 0 1 1 0-4 2 2 0 0 1 0 4z"
            fill="currentColor"
          />
        </svg>
      </div>
      <div className="flex-1 min-w-0 rounded-2xl rounded-tl-sm border border-border bg-panel px-4 py-3.5">
        {parsed ? (
          <AnswerCard
            answer={parsed}
            evidence={extras?.evidence ?? []}
            onOpenEvidence={onOpenEvidence}
            onFollowUpClick={onFollowUpClick}
          />
        ) : (
          <p className="text-sm leading-relaxed">{message.content}</p>
        )}
      </div>
    </div>
  );
}

function safeParse(json: string) {
  try {
    return JSON.parse(json);
  } catch {
    return null;
  }
}
