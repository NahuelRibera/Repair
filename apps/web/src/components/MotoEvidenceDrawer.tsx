import type { MotoEvidenceCard, MotoRagRunDebug } from "@/lib/types";

/** Developer/debug evidence drawer for the motorcycle chat — see
 * docs/repair-v2-architecture.md section on debug/evidence. Shows exactly
 * which model/year was selected and which chunks were retrieved, so a
 * contamination bug (e.g. MT-09 content under an MT-07 selection) would
 * be immediately visible here. Never shown in the normal customer-facing
 * chat bubble — only behind this explicit "View evidence & debug" action. */
export function MotoEvidenceDrawer({
  evidence,
  debug,
  onClose,
}: {
  evidence: MotoEvidenceCard[];
  debug: MotoRagRunDebug | null;
  onClose: () => void;
}) {
  return (
    <div className="fixed inset-0 z-50 flex justify-end bg-black/40" onClick={onClose}>
      <div
        className="h-full w-full max-w-md bg-panel border-l border-border overflow-y-auto"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="sticky top-0 bg-panel border-b border-border px-5 py-4 flex items-center justify-between">
          <h2 className="font-semibold">Evidence &amp; Debug</h2>
          <button onClick={onClose} aria-label="Close" className="p-1 text-muted hover:text-foreground">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
              <path d="M6 6l12 12M18 6L6 18" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
            </svg>
          </button>
        </div>

        <div className="p-5 space-y-6">
          {debug && (
            <section>
              <h3 className="text-xs font-semibold text-muted mb-2 uppercase tracking-wide">Selected</h3>
              <dl className="text-sm space-y-1.5">
                <Row label="Bike" value={`${debug.manufacturerName} / ${debug.modelName} / ${debug.year}`} />
                <Row label="Request ID" value={debug.requestId} mono />
                <Row label="Provider status" value={<StatusBadge status={debug.providerStatus} />} />
                <Row label="Embedding model" value={debug.embeddingModel ?? "—"} />
                <Row label="Generation model" value={debug.generationModel ?? "—"} />
                <Row
                  label="Retrieval time"
                  value={debug.retrievalMillis != null ? `${Math.round(debug.retrievalMillis)} ms` : "—"}
                />
                <Row
                  label="Generation time"
                  value={debug.generationMillis != null ? `${Math.round(debug.generationMillis)} ms` : "—"}
                />
                <Row
                  label="Tokens (prompt / completion)"
                  value={debug.promptTokens != null ? `${debug.promptTokens} / ${debug.completionTokens ?? 0}` : "—"}
                />
                {debug.errorDetail && <Row label="Error" value={debug.errorDetail} />}
              </dl>
            </section>
          )}

          <section>
            <h3 className="text-xs font-semibold text-muted mb-2 uppercase tracking-wide">
              Retrieved ({evidence.length})
            </h3>
            {evidence.length === 0 && <p className="text-sm text-muted">No evidence was retrieved for this turn.</p>}
            <div className="space-y-3">
              {evidence.map((e) => (
                <div key={e.chunkId} className="rounded-lg border border-border p-3">
                  <div className="flex items-start justify-between gap-2 mb-1">
                    <p className="text-sm font-medium">
                      {debug ? `${debug.modelName} / ${debug.year}` : "—"}
                      {e.section ? ` / ${e.section}` : ""}
                    </p>
                    <span className="text-[10px] shrink-0 rounded bg-black/5 px-1.5 py-0.5 text-muted">
                      #{e.chunkId}
                    </span>
                  </div>
                  {e.subsection && <p className="text-xs text-muted mb-1.5">{e.sectionPath}</p>}
                  <p className="text-sm text-foreground/80 leading-relaxed line-clamp-4">{e.excerpt}</p>
                  <div className="flex items-center gap-3 mt-2 text-[11px] text-muted">
                    <span title="Retrieval score, not a probability of correctness">
                      fused score: {e.fusedScore.toFixed(3)}
                    </span>
                    {e.vectorScore != null && <span>vector: {e.vectorScore.toFixed(3)}</span>}
                    {e.textScore != null && <span>text: {e.textScore.toFixed(3)}</span>}
                  </div>
                  <span className="inline-block mt-2 text-[10px] rounded bg-black/5 px-1.5 py-0.5">{e.category}</span>
                </div>
              ))}
            </div>
          </section>

          <p className="text-[11px] text-muted leading-relaxed">
            Retrieval scores measure text/vector similarity to the question, not correctness. A result outside{" "}
            {debug ? `${debug.modelName} / ${debug.year}` : "the selected bike"} here would indicate a retrieval bug —
            evidence is always hard-filtered to the selected model and year before ranking.
          </p>
        </div>
      </div>
    </div>
  );
}

function Row({ label, value, mono }: { label: string; value: React.ReactNode; mono?: boolean }) {
  return (
    <div className="flex items-start justify-between gap-3">
      <dt className="text-muted shrink-0">{label}</dt>
      <dd className={`text-right ${mono ? "font-mono text-xs" : ""}`}>{value}</dd>
    </div>
  );
}

function StatusBadge({ status }: { status: string }) {
  const ok = status === "ok";
  return (
    <span className={`rounded px-1.5 py-0.5 text-xs font-medium ${ok ? "bg-green-100 text-green-700" : "bg-amber-100 text-amber-800"}`}>
      {status}
    </span>
  );
}
