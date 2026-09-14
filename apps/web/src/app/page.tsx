import Link from "next/link";
import { Logo } from "@/components/Logo";
import { LandingPicker } from "@/components/LandingPicker";

export default function HomePage() {
  return (
    <div className="flex-1 flex flex-col">
      <header className="border-b border-border bg-panel">
        <div className="mx-auto max-w-7xl px-4 sm:px-6 py-4 flex items-center justify-between">
          <Logo />
          <nav className="hidden sm:flex items-center gap-6 text-sm text-muted">
            <a href="#features" className="hover:text-foreground">
              Features
            </a>
            <a href="#how-it-works" className="hover:text-foreground">
              How it works
            </a>
          </nav>
          <Link
            href="/chat"
            className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-hover transition-colors"
          >
            Open the app
          </Link>
        </div>
      </header>

      <section className="relative flex-1 bg-navy overflow-hidden">
        <div
          className="absolute inset-0 opacity-60"
          style={{
            background:
              "radial-gradient(ellipse 80% 60% at 20% 20%, rgba(37,99,235,0.25), transparent), radial-gradient(ellipse 60% 50% at 90% 80%, rgba(37,99,235,0.15), transparent)",
          }}
        />
        <div className="relative mx-auto max-w-7xl px-4 sm:px-6 py-14 sm:py-20 grid grid-cols-1 lg:grid-cols-[1.1fr_0.9fr] gap-12 items-start">
          <div>
            <p className="text-xs font-semibold tracking-[0.2em] text-accent mb-4">
              MAINTENANCE · OWNERSHIP · TROUBLESHOOTING
            </p>
            <h1 className="text-4xl sm:text-5xl font-bold text-white leading-tight mb-5">
              Your bike, properly looked after.
            </h1>
            <p className="text-white/70 text-lg max-w-xl mb-8">
              Maintenance guidance, service tracking and everyday troubleshooting built around
              the exact motorcycle you ride — grounded in verified, bike-specific knowledge, not
              a generic chatbot guessing at your model.
            </p>
            <div className="flex flex-wrap gap-3 mb-10">
              <Link
                href="/chat"
                className="rounded-lg bg-accent px-5 py-3 text-sm font-semibold text-white hover:bg-accent-hover transition-colors"
              >
                Choose your bike →
              </Link>
              <Link
                href="/garage"
                className="rounded-lg border border-white/20 px-5 py-3 text-sm font-semibold text-white hover:bg-white/10 transition-colors"
              >
                My Garage
              </Link>
            </div>
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-6 text-white/80 text-sm max-w-xl">
              <Feature title="Know what's due" desc="Service intervals and status tracked against your bike's real history." />
              <Feature title="Remember what you've done" desc="Maintenance you mention in chat is saved to your garage automatically." />
              <Feature title="Understand your bike" desc="Answers grounded in verified, model-and-year-specific knowledge, never invented specs." />
            </div>
          </div>

          <div className="rounded-2xl border border-white/10 bg-navy-2 p-5 sm:p-6 shadow-2xl">
            <div className="flex items-center gap-3 mb-1">
              <span className="flex h-9 w-9 items-center justify-center rounded-full bg-accent/20 text-accent">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
                  <path
                    d="M21.7 16.3l-4-4a5 5 0 0 0-6.2-6.2L8.4 9.2 4.9 5.7 2.3 8.3l3.5 3.5-3.1 3.1a5 5 0 0 0 6.2 6.2l4-4 4 4 4.8-4.8zM8 20a2 2 0 1 1 0-4 2 2 0 0 1 0 4z"
                    fill="currentColor"
                  />
                </svg>
              </span>
              <h2 className="text-white font-semibold">Choose your bike</h2>
            </div>
            <p className="text-white/50 text-sm mb-5">Manufacturer, model and year — then just ask.</p>
            <LandingPicker />
          </div>
        </div>
      </section>

      <section id="features" className="bg-background py-16">
        <div className="mx-auto max-w-7xl px-4 sm:px-6">
          <p className="text-xs font-semibold tracking-[0.2em] text-accent mb-3">HOW IT WORKS</p>
          <h2 className="text-2xl sm:text-3xl font-bold mb-10">Ask what your bike needs, in plain language</h2>
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-6">
            <Step
              n="1"
              title="Choose your bike"
              desc="Manufacturer, model and year, from a dynamic catalog built entirely from verified knowledge documents."
            />
            <Step
              n="2"
              title="Ask naturally"
              desc="Oil changes, chain slack, tire pressure, a symptom that's bothering you — Repair already knows which bike you mean."
            />
            <Step
              n="3"
              title="Get a grounded answer"
              desc="Explained conversationally, backed by your bike's exact specifications, never a different model or a guess."
            />
          </div>
        </div>
      </section>

      <footer className="border-t border-border py-8">
        <div className="mx-auto max-w-7xl px-4 sm:px-6 flex flex-col sm:flex-row items-center justify-between gap-4 text-sm text-muted">
          <Logo />
          <p className="text-center sm:text-right">
            This is a portfolio demo. Guidance is AI-generated from a curated knowledge base and is not a substitute
            for a qualified mechanic.
          </p>
        </div>
      </footer>
    </div>
  );
}

function Feature({ title, desc }: { title: string; desc: string }) {
  return (
    <div>
      <p className="font-semibold text-white mb-1">{title}</p>
      <p className="text-white/60 text-xs leading-relaxed">{desc}</p>
    </div>
  );
}

function Step({ n, title, desc }: { n: string; title: string; desc: string }) {
  return (
    <div className="rounded-xl border border-border bg-panel p-6">
      <span className="flex h-8 w-8 items-center justify-center rounded-full bg-navy text-white text-sm font-semibold mb-4">
        {n}
      </span>
      <p className="font-semibold mb-1.5">{title}</p>
      <p className="text-sm text-muted leading-relaxed">{desc}</p>
    </div>
  );
}
