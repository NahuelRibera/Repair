import type { ActionTaken, MotoDiagnosticAnswer, MotoEvidenceCard } from "@/lib/types";
import { SERVICE_TYPE_LABELS } from "@/lib/types";

const ANSWER_TYPE_STYLES: Record<string, { label: string; className: string }> = {
  clarification: { label: "Needs clarification", className: "bg-blue-50 text-blue-700 border-blue-200" },
  guidance: { label: "Guidance", className: "bg-green-50 text-green-700 border-green-200" },
  insufficient_evidence: { label: "Insufficient evidence", className: "bg-gray-100 text-gray-700 border-gray-300" },
  safety_referral: { label: "Safety referral", className: "bg-red-50 text-red-700 border-red-200" },
};

export function MotoAnswerCard({
  answer,
  evidence,
  actionsTaken,
  onOpenEvidence,
  onFollowUpClick,
}: {
  answer: MotoDiagnosticAnswer;
  evidence: MotoEvidenceCard[];
  actionsTaken: ActionTaken[];
  onOpenEvidence: () => void;
  onFollowUpClick: (question: string) => void;
}) {
  const style = ANSWER_TYPE_STYLES[answer.answerType] ?? ANSWER_TYPE_STYLES.insufficient_evidence;

  return (
    <div className="space-y-4">
      <span className={`inline-flex items-center rounded-full border px-2.5 py-1 text-xs font-medium ${style.className}`}>
        {style.label}
      </span>

      <p className="text-[15px] leading-relaxed">{answer.summary}</p>

      {actionsTaken.length > 0 && (
        <div className="flex flex-wrap gap-2">
          {actionsTaken.map((a, i) => (
            <span
              key={i}
              className="inline-flex items-center gap-1.5 rounded-full bg-green-50 border border-green-200 text-green-700 px-2.5 py-1 text-xs font-medium"
            >
              <span aria-hidden>✓</span>
              {actionLabel(a)}
            </span>
          ))}
        </div>
      )}

      {answer.confirmedFacts.length > 0 && (
        <div>
          <p className="text-xs font-semibold text-muted mb-1.5">Bike-specific facts used</p>
          <div className="flex flex-wrap gap-1.5">
            {answer.confirmedFacts.map((s, i) => (
              <span key={i} className="rounded-full bg-black/5 px-2.5 py-1 text-xs">
                {s}
              </span>
            ))}
          </div>
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

function actionLabel(action: ActionTaken): string {
  if (action.type === "maintenance_event_created") {
    const label = action.serviceType ? SERVICE_TYPE_LABELS[action.serviceType] : "Maintenance";
    return action.odometerKm != null ? `${label} saved · ${formatKm(action.odometerKm)} km` : `${label} saved`;
  }
  if (action.type === "odometer_updated") {
    return `Odometer updated · ${formatKm(action.odometerKm ?? 0)} km`;
  }
  if (action.type === "preference_saved") {
    return `Preference saved${action.detail ? ` · ${action.detail}` : ""}`;
  }
  return "Saved";
}

function formatKm(value: number): string {
  return Math.round(value).toLocaleString("en-US");
}
