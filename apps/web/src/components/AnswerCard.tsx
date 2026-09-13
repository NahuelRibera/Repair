import type { DiagnosticAnswer, EvidenceCard } from "@/lib/types";

const ANSWER_TYPE_STYLES: Record<string, { label: string; className: string }> = {
  clarification: { label: "Needs clarification", className: "bg-blue-50 text-blue-700 border-blue-200" },
  guidance: { label: "Guidance", className: "bg-green-50 text-green-700 border-green-200" },
  insufficient_evidence: { label: "Insufficient evidence", className: "bg-gray-100 text-gray-700 border-gray-300" },
  safety_referral: { label: "Safety referral", className: "bg-red-50 text-red-700 border-red-200" },
};

export function AnswerCard({
  answer,
  evidence,
  onOpenEvidence,
  onFollowUpClick,
}: {
  answer: DiagnosticAnswer;
  evidence: EvidenceCard[];
  onOpenEvidence: () => void;
  onFollowUpClick: (question: string) => void;
}) {
  const style = ANSWER_TYPE_STYLES[answer.answerType] ?? ANSWER_TYPE_STYLES.insufficient_evidence;
  const evidenceByChunkId = new Map(evidence.map((e) => [e.chunkId, e]));

  return (
    <div className="space-y-4">
      <span className={`inline-flex items-center rounded-full border px-2.5 py-1 text-xs font-medium ${style.className}`}>
        {style.label}
      </span>

      <p className="text-[15px] leading-relaxed">{answer.summary}</p>

      {answer.confirmedSymptoms.length > 0 && (
        <div>
          <p className="text-xs font-semibold text-muted mb-1.5">Confirmed so far</p>
          <div className="flex flex-wrap gap-1.5">
            {answer.confirmedSymptoms.map((s, i) => (
              <span key={i} className="rounded-full bg-black/5 px-2.5 py-1 text-xs">
                {s}
              </span>
            ))}
          </div>
        </div>
      )}

      {answer.hypotheses.length > 0 && (
        <div>
          <p className="text-xs font-semibold text-muted mb-2">Possible causes</p>
          <ul className="space-y-2.5">
            {answer.hypotheses.map((h, i) => (
              <li key={i} className="rounded-lg border border-border p-3">
                <p className="text-sm font-medium mb-1">{h.description}</p>
                <p className="text-sm text-muted leading-relaxed">{h.reasoning}</p>
                {h.evidenceChunkIds.length > 0 && (
                  <div className="flex flex-wrap gap-1 mt-2">
                    {h.evidenceChunkIds.map((id) => {
                      const ev = evidenceByChunkId.get(id);
                      return (
                        <button
                          key={id}
                          onClick={onOpenEvidence}
                          className="text-xs rounded border border-accent/30 bg-accent/5 text-accent px-1.5 py-0.5 hover:bg-accent/10"
                          title={ev?.documentTitle}
                        >
                          [{id}]
                        </button>
                      );
                    })}
                  </div>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}

      {answer.safeChecks.length > 0 && (
        <div>
          <p className="text-xs font-semibold text-muted mb-1.5">Safe checks you can do</p>
          <ul className="space-y-1">
            {answer.safeChecks.map((c, i) => (
              <li key={i} className="flex items-start gap-2 text-sm">
                <span className="mt-0.5 text-green-600" aria-hidden>
                  ✓
                </span>
                <span>{c}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {answer.cautions.length > 0 && (
        <div className="rounded-lg bg-amber-50 border border-amber-200 p-3">
          <p className="text-xs font-semibold text-amber-800 mb-1">Cautions</p>
          <ul className="space-y-1">
            {answer.cautions.map((c, i) => (
              <li key={i} className="text-sm text-amber-900">
                {c}
              </li>
            ))}
          </ul>
        </div>
      )}

      {answer.missingInformation.length > 0 && (
        <div>
          <p className="text-xs font-semibold text-muted mb-1.5">Still missing</p>
          <ul className="space-y-1">
            {answer.missingInformation.map((m, i) => (
              <li key={i} className="text-sm text-muted">
                • {m}
              </li>
            ))}
          </ul>
        </div>
      )}

      {answer.followUpQuestions.length > 0 && (
        <div className="flex flex-wrap gap-2">
          {answer.followUpQuestions.map((q, i) => (
            <button
              key={i}
              onClick={() => onFollowUpClick(q)}
              className="rounded-full border border-accent/30 bg-accent/5 text-accent px-3 py-1.5 text-sm hover:bg-accent/10"
            >
              {q}
            </button>
          ))}
        </div>
      )}

      {evidence.length > 0 && (
        <button
          onClick={onOpenEvidence}
          className="text-sm font-medium text-accent hover:underline flex items-center gap-1"
        >
          View evidence &amp; debug ({evidence.length})
        </button>
      )}
    </div>
  );
}
