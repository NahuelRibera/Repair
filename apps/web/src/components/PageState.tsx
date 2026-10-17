export function LoadingState({ label }: { label: string }) {
  return (
    <div className="flex-1 flex items-center justify-center p-6" role="status" aria-live="polite">
      <p className="text-sm text-muted">{label}</p>
    </div>
  );
}

export function ErrorState({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="flex-1 flex flex-col items-center justify-center gap-4 p-6 text-center">
      <p className="text-sm text-red-700" role="alert">
        {message}
      </p>
      <button
        type="button"
        onClick={onRetry}
        className="rounded-lg border border-border px-4 py-2 text-sm font-medium hover:bg-black/5 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
      >
        Try again
      </button>
    </div>
  );
}
