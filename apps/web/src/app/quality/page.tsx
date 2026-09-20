"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { RepairLogo } from "@/components/RepairLogo";
import { vehicleTitle } from "@/lib/types";
import type { DataQualityIssue, DataQualitySummary, PageResult } from "@/lib/types";

const PAGE_SIZE = 20;

export default function DataQualityPage() {
  const [summary, setSummary] = useState<DataQualitySummary | null>(null);
  const [issues, setIssues] = useState<PageResult<DataQualityIssue> | null>(null);
  const [rule, setRule] = useState("");
  const [severity, setSeverity] = useState("");
  const [page, setPage] = useState(0);

  useEffect(() => {
    api.get<DataQualitySummary>("/api/data-quality/summary").then(setSummary);
  }, []);

  useEffect(() => {
    const params = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
    if (rule) params.set("rule", rule);
    if (severity) params.set("severity", severity);
    api.get<PageResult<DataQualityIssue>>(`/api/data-quality/issues?${params}`).then(setIssues);
  }, [rule, severity, page]);

  return (
    <div className="flex-1 flex flex-col">
      <header className="border-b border-border bg-panel">
        <div className="mx-auto max-w-6xl px-4 sm:px-6 py-4 flex items-center justify-between">
          <RepairLogo />
          <Link href="/chat" className="text-sm font-medium text-accent hover:underline">
            ← Back to app
          </Link>
        </div>
      </header>

      <div className="mx-auto max-w-6xl w-full px-4 sm:px-6 py-10 flex-1">
        <h1 className="text-2xl font-bold mb-1">Data quality</h1>
        <p className="text-muted text-sm mb-8">
          Real import totals and flagged review candidates from the catalogue ingestion pipeline. Nothing here is
          auto-corrected — every row is a candidate for human review.
        </p>

        {summary && (
          <div className="grid grid-cols-2 sm:grid-cols-5 gap-3 mb-8">
            <StatCard label="Manufacturers" value={summary.manufacturers} />
            <StatCard label="Models" value={summary.models} />
            <StatCard label="Variants" value={summary.variants} />
            <StatCard label="Raw records" value={summary.rawRecords} />
            <StatCard label="Flagged issues" value={summary.totalIssues} accent />
          </div>
        )}

        {summary && (
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 mb-8">
            <BreakdownCard title="By rule" rows={summary.issuesByRule} onClickRow={(k) => setRule(k)} />
            <BreakdownCard title="By severity" rows={summary.issuesBySeverity} onClickRow={(k) => setSeverity(k)} />
          </div>
        )}

        <div className="flex flex-wrap items-center gap-3 mb-4">
          <select
            value={rule}
            onChange={(e) => {
              setRule(e.target.value);
              setPage(0);
            }}
            className="rounded-lg border border-border bg-panel px-3 py-2 text-sm"
          >
            <option value="">All rules</option>
            {summary?.issuesByRule.map((r) => (
              <option key={r.key} value={r.key}>
                {r.key} ({r.count})
              </option>
            ))}
          </select>
          <select
            value={severity}
            onChange={(e) => {
              setSeverity(e.target.value);
              setPage(0);
            }}
            className="rounded-lg border border-border bg-panel px-3 py-2 text-sm"
          >
            <option value="">All severities</option>
            {summary?.issuesBySeverity.map((s) => (
              <option key={s.key} value={s.key}>
                {s.key} ({s.count})
              </option>
            ))}
          </select>
          {(rule || severity) && (
            <button
              onClick={() => {
                setRule("");
                setSeverity("");
                setPage(0);
              }}
              className="text-sm text-accent hover:underline"
            >
              Clear filters
            </button>
          )}
        </div>

        <div className="rounded-xl border border-border overflow-hidden overflow-x-auto">
          <table className="w-full text-sm min-w-[720px]">
            <thead className="bg-black/[0.02] border-b border-border text-left text-xs text-muted uppercase tracking-wide">
              <tr>
                <th className="px-4 py-2.5">Rule</th>
                <th className="px-4 py-2.5">Severity</th>
                <th className="px-4 py-2.5">Vehicle</th>
                <th className="px-4 py-2.5">Field</th>
                <th className="px-4 py-2.5">Observed value</th>
                <th className="px-4 py-2.5">Explanation</th>
              </tr>
            </thead>
            <tbody>
              {issues?.items.map((issue) => (
                <tr key={issue.id} className="border-b border-border last:border-0">
                  <td className="px-4 py-2.5 font-mono text-xs">{issue.rule}</td>
                  <td className="px-4 py-2.5">
                    <SeverityBadge severity={issue.severity} />
                  </td>
                  <td className="px-4 py-2.5 text-xs">
                    {issue.manufacturerName ? vehicleTitle(issue.manufacturerName, issue.modelName ?? "") : "—"}
                  </td>
                  <td className="px-4 py-2.5 text-xs">{issue.field ?? "—"}</td>
                  <td className="px-4 py-2.5 text-xs max-w-[220px] truncate" title={issue.observedValuePreview ?? ""}>
                    {issue.observedValuePreview ?? "—"}
                  </td>
                  <td className="px-4 py-2.5 text-xs text-muted max-w-[320px]">{issue.explanation}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {issues && (
          <div className="flex items-center justify-between mt-4 text-sm text-muted">
            <span>
              {issues.total === 0 ? 0 : page * PAGE_SIZE + 1}–{Math.min((page + 1) * PAGE_SIZE, issues.total)} of{" "}
              {issues.total}
            </span>
            <div className="flex gap-2">
              <button
                disabled={page === 0}
                onClick={() => setPage((p) => p - 1)}
                className="rounded-lg border border-border px-3 py-1.5 disabled:opacity-40"
              >
                Previous
              </button>
              <button
                disabled={(page + 1) * PAGE_SIZE >= issues.total}
                onClick={() => setPage((p) => p + 1)}
                className="rounded-lg border border-border px-3 py-1.5 disabled:opacity-40"
              >
                Next
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

function StatCard({ label, value, accent }: { label: string; value: number; accent?: boolean }) {
  return (
    <div className="rounded-xl border border-border bg-panel p-4">
      <p className={`text-2xl font-bold ${accent ? "text-accent" : ""}`}>{value.toLocaleString()}</p>
      <p className="text-xs text-muted mt-0.5">{label}</p>
    </div>
  );
}

function BreakdownCard({
  title,
  rows,
  onClickRow,
}: {
  title: string;
  rows: { key: string; count: number }[];
  onClickRow: (key: string) => void;
}) {
  const max = Math.max(...rows.map((r) => r.count), 1);
  return (
    <div className="rounded-xl border border-border bg-panel p-4">
      <p className="text-xs font-semibold text-muted uppercase tracking-wide mb-3">{title}</p>
      <div className="space-y-2">
        {rows.map((r) => (
          <button
            key={r.key}
            onClick={() => onClickRow(r.key)}
            className="w-full text-left group"
          >
            <div className="flex items-center justify-between text-xs mb-1">
              <span className="font-mono group-hover:text-accent">{r.key}</span>
              <span className="text-muted">{r.count.toLocaleString()}</span>
            </div>
            <div className="h-1.5 rounded-full bg-black/5 overflow-hidden">
              <div className="h-full bg-accent/60" style={{ width: `${(r.count / max) * 100}%` }} />
            </div>
          </button>
        ))}
      </div>
    </div>
  );
}

function SeverityBadge({ severity }: { severity: string }) {
  const styles: Record<string, string> = {
    error: "bg-red-100 text-red-700",
    warning: "bg-amber-100 text-amber-800",
    info: "bg-gray-100 text-gray-700",
  };
  return (
    <span className={`rounded px-1.5 py-0.5 text-xs font-medium ${styles[severity] ?? styles.info}`}>{severity}</span>
  );
}
