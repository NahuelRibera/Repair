import Link from "next/link";

export function Logo({ dark = false }: { dark?: boolean }) {
  return (
    <Link href="/" className="flex items-center gap-2.5 shrink-0">
      <span
        className={`flex h-8 w-8 items-center justify-center rounded-lg ${
          dark ? "bg-white text-navy" : "bg-navy text-white"
        }`}
        aria-hidden
      >
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
          <path
            d="M21.7 16.3l-4-4a5 5 0 0 0-6.2-6.2L8.4 9.2 4.9 5.7 2.3 8.3l3.5 3.5-3.1 3.1a5 5 0 0 0 6.2 6.2l4-4 4 4 4.8-4.8zM8 20a2 2 0 1 1 0-4 2 2 0 0 1 0 4z"
            fill="currentColor"
          />
        </svg>
      </span>
      <span className="leading-tight">
        <span className={`block font-semibold text-[15px] ${dark ? "text-white" : "text-navy"}`}>Repair</span>
        <span className={`block text-[11px] ${dark ? "text-white/60" : "text-muted"}`}>Your car, understood.</span>
      </span>
    </Link>
  );
}
