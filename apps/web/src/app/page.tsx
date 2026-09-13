import Link from "next/link";
import { Logo } from "@/components/Logo";
import { LandingPicker } from "@/components/LandingPicker";

const EXAMPLES = [
  "The driver's window won't go up — what could it be?",
  "The engine warning light just came on, what does that mean?",
  "I feel a vibration when braking at highway speed",
  "There's a knocking noise over bumps at low speed",
  "What oil does my car take, and how much?",
];

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
              DIAGNOSE · LEARN · REPAIR
            </p>
            <h1 className="text-4xl sm:text-5xl font-bold text-white leading-tight mb-5">
              AI-assisted diagnosis for real cars
            </h1>
            <p className="text-white/70 text-lg max-w-xl mb-8">
              Describe a symptom on your car and get evidence-backed hypotheses, safe checks
              to run yourself, and cited sources — not a guess dressed up as an answer.
            </p>
            <div className="flex flex-wrap gap-3 mb-10">
              <Link
                href="/chat"
                className="rounded-lg bg-accent px-5 py-3 text-sm font-semibold text-white hover:bg-accent-hover transition-colors"
              >
                Start a diagnosis →
              </Link>
              <Link
                href="/quality"
                className="rounded-lg border border-white/20 px-5 py-3 text-sm font-semibold text-white hover:bg-white/10 transition-colors"
              >
                View data quality
              </Link>
            </div>
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-6 text-white/80 text-sm max-w-xl">
              <Feature title="Real catalogue data" desc="Backed by a reconciled, source-tracked vehicle database." />
              <Feature title="Cited evidence" desc="Every hypothesis links back to the excerpt that supports it." />
              <Feature title="Cars, one demo vehicle" desc="A BMW 3 Series (E90) 320d has full scenario coverage today." />
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
              <h2 className="text-white font-semibold">Start resolving your issue</h2>
            </div>
            <p className="text-white/50 text-sm mb-5">Select your vehicle or jump straight to an example.</p>
            <LandingPicker examples={EXAMPLES} />
          </div>
        </div>
      </section>

      <section id="features" className="bg-background py-16">
        <div className="mx-auto max-w-7xl px-4 sm:px-6">
          <p className="text-xs font-semibold tracking-[0.2em] text-accent mb-3">HOW IT WORKS</p>
          <h2 className="text-2xl sm:text-3xl font-bold mb-10">From symptom to evidence-backed answer</h2>
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-6">
            <Step
              n="1"
              title="Select your vehicle"
              desc="Search the manufacturer, model, year, and version from a real, reconciled catalogue."
            />
            <Step
              n="2"
              title="Describe the symptom"
              desc="Answer one or two follow-up questions if the assistant needs more detail."
            />
            <Step
              n="3"
              title="Get a structured answer"
              desc="Hypotheses, safe checks, cautions, and the exact evidence each claim is based on."
            />
          </div>
        </div>
      </section>

      <footer className="border-t border-border py-8">
        <div className="mx-auto max-w-7xl px-4 sm:px-6 flex flex-col sm:flex-row items-center justify-between gap-4 text-sm text-muted">
          <Logo />
          <p className="text-center sm:text-right">
            This is a portfolio demo. Diagnoses are AI-generated from a limited knowledge base and are not a
            substitute for a professional inspection.
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
