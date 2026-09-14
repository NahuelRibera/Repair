"use client";

import { useRef, useState } from "react";

const MAX_LENGTH = 2000;

export function Composer({
  disabled,
  initialValue,
  onSend,
}: {
  disabled: boolean;
  initialValue?: string;
  onSend: (text: string) => void;
}) {
  // initialValue only ever comes from the prefill query param and is fixed
  // for the lifetime of this component, so a one-time useState initializer
  // is sufficient — no effect needed to keep it in sync.
  const [value, setValue] = useState(initialValue ?? "");
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  function submit() {
    const trimmed = value.trim();
    if (!trimmed || disabled) return;
    onSend(trimmed);
    setValue("");
  }

  return (
    <div className="border-t border-border bg-panel p-3 sm:p-4 shrink-0">
      <div className="mx-auto max-w-3xl">
        <div className="flex items-end gap-2 rounded-xl border border-border bg-panel px-3 py-2 focus-within:border-accent/50">
          <textarea
            ref={textareaRef}
            value={value}
            maxLength={MAX_LENGTH}
            disabled={disabled}
            onChange={(e) => setValue(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                submit();
              }
            }}
            placeholder="Ask about maintenance, or describe what's happening…"
            rows={1}
            className="flex-1 resize-none bg-transparent py-1.5 text-sm outline-none max-h-40 disabled:opacity-50"
          />
          <button
            type="button"
            onClick={submit}
            disabled={disabled || !value.trim()}
            aria-label="Send message"
            className="shrink-0 flex h-9 w-9 items-center justify-center rounded-lg bg-accent text-white disabled:opacity-30 hover:bg-accent-hover transition-colors"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
              <path d="M4 12h15m0 0l-6-6m6 6l-6 6" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </button>
        </div>
        <p className="text-[11px] text-muted mt-2 text-center">
          Repair can make mistakes. Always verify safety-critical information. {value.length}/{MAX_LENGTH}
        </p>
      </div>
    </div>
  );
}
