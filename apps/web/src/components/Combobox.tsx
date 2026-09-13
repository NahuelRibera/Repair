"use client";

import { useEffect, useId, useRef, useState } from "react";

export interface ComboboxOption {
  id: number;
  label: string;
  sublabel?: string;
}

interface ComboboxProps {
  label: string;
  placeholder: string;
  value: ComboboxOption | null;
  disabled?: boolean;
  /** Shown when a search returns zero results. Defaults to "No matches". */
  emptyMessage?: string;
  fetchOptions: (search: string) => Promise<ComboboxOption[]>;
  onSelect: (option: ComboboxOption) => void;
}

type LoadState = "idle" | "loading" | "loaded" | "error";

export function Combobox({
  label, placeholder, value, disabled, emptyMessage, fetchOptions, onSelect,
}: ComboboxProps) {
  const [query, setQuery] = useState("");
  const [open, setOpen] = useState(false);
  const [options, setOptions] = useState<ComboboxOption[]>([]);
  const [loadState, setLoadState] = useState<LoadState>("idle");
  const [highlightedIndex, setHighlightedIndex] = useState(-1);
  const containerRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const requestId = useRef(0);
  const listboxId = useId();

  function load() {
    const id = ++requestId.current;
    setLoadState("loading");
    fetchOptions(query)
      .then((results) => {
        if (requestId.current !== id) return;
        setOptions(results);
        setLoadState("loaded");
        setHighlightedIndex(results.length > 0 ? 0 : -1);
      })
      .catch(() => {
        if (requestId.current !== id) return;
        setLoadState("error");
      });
  }

  useEffect(() => {
    if (!open) return;
    const timer = setTimeout(load, 150);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query, open, fetchOptions]);

  useEffect(() => {
    function handleClick(e: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    }
    document.addEventListener("mousedown", handleClick);
    return () => document.removeEventListener("mousedown", handleClick);
  }, []);

  function openDropdown() {
    setOpen(true);
    setQuery("");
  }

  function closeDropdown(returnFocus: boolean) {
    setOpen(false);
    if (returnFocus) triggerRef.current?.focus();
  }

  function selectOption(opt: ComboboxOption) {
    onSelect(opt);
    closeDropdown(false);
  }

  function handleInputKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    switch (e.key) {
      case "ArrowDown":
        e.preventDefault();
        setHighlightedIndex((i) => (options.length === 0 ? -1 : Math.min(i + 1, options.length - 1)));
        break;
      case "ArrowUp":
        e.preventDefault();
        setHighlightedIndex((i) => (options.length === 0 ? -1 : Math.max(i - 1, 0)));
        break;
      case "Enter":
        e.preventDefault();
        if (highlightedIndex >= 0 && highlightedIndex < options.length) {
          selectOption(options[highlightedIndex]);
        }
        break;
      case "Escape":
        e.preventDefault();
        closeDropdown(true);
        break;
    }
  }

  const activeOptionId = highlightedIndex >= 0 ? `${listboxId}-option-${highlightedIndex}` : undefined;

  return (
    <div ref={containerRef} className="relative">
      <label className="block text-xs font-medium text-muted mb-1">{label}</label>
      <button
        ref={triggerRef}
        type="button"
        disabled={disabled}
        onClick={() => (open ? closeDropdown(false) : openDropdown())}
        className="w-full flex items-center justify-between rounded-lg border border-border bg-panel px-3 py-2.5 text-left text-sm disabled:cursor-not-allowed disabled:opacity-50 hover:border-accent/50 transition-colors"
      >
        <span className={value ? "text-foreground" : "text-muted"}>{value ? value.label : placeholder}</span>
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" className="text-muted shrink-0 ml-2">
          <path d="M6 9l6 6 6-6" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
        </svg>
      </button>
      {open && (
        <div className="absolute z-20 mt-1 w-full rounded-lg border border-border bg-panel shadow-lg overflow-hidden">
          <input
            ref={inputRef}
            autoFocus
            role="combobox"
            aria-expanded={open}
            aria-controls={listboxId}
            aria-activedescendant={activeOptionId}
            aria-label={label}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={handleInputKeyDown}
            placeholder="Type to search, or browse below…"
            className="w-full border-b border-border px-3 py-2 text-sm outline-none"
          />
          <div id={listboxId} role="listbox" aria-label={label} className="max-h-64 overflow-y-auto overscroll-contain">
            {loadState === "loading" && <div className="px-3 py-2 text-sm text-muted">Loading…</div>}
            {loadState === "error" && (
              <div className="px-3 py-2 text-sm">
                <p className="text-red-600 mb-1.5">Couldn&apos;t load options.</p>
                <button
                  type="button"
                  onClick={load}
                  className="text-accent font-medium hover:underline"
                >
                  Retry
                </button>
              </div>
            )}
            {loadState === "loaded" && options.length === 0 && (
              <div className="px-3 py-2 text-sm text-muted">{emptyMessage ?? "No matches"}</div>
            )}
            {loadState === "loaded" &&
              options.map((opt, index) => (
                <button
                  key={opt.id}
                  id={`${listboxId}-option-${index}`}
                  role="option"
                  aria-selected={value?.id === opt.id}
                  type="button"
                  onMouseEnter={() => setHighlightedIndex(index)}
                  onClick={() => selectOption(opt)}
                  className={`w-full text-left px-3 py-2 text-sm flex flex-col ${
                    index === highlightedIndex ? "bg-accent/10" : "hover:bg-accent/5"
                  }`}
                >
                  <span>{opt.label}</span>
                  {opt.sublabel && <span className="text-xs text-muted">{opt.sublabel}</span>}
                </button>
              ))}
          </div>
        </div>
      )}
    </div>
  );
}
