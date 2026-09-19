import Image from "next/image";
import Link from "next/link";
import { RepairLogo } from "@/components/RepairLogo";
import { LandingPicker } from "@/components/LandingPicker";
import { LandingNavControls } from "@/components/LandingNavControls";

export default function HomePage() {
  return (
    <div className="flex-1 flex flex-col">
      <header className="border-b border-border bg-panel sticky top-0 z-20">
        <div className="mx-auto max-w-7xl px-4 sm:px-6 py-4 flex items-center justify-between gap-4">
          <RepairLogo />
          <nav className="hidden sm:flex items-center gap-6 text-sm text-muted">
            <a href="#features" className="hover:text-foreground">
              Features
            </a>
            <a href="#how-it-works" className="hover:text-foreground">
              How it works
            </a>
            <a href="#from-the-garage" className="hover:text-foreground">
              From the garage
            </a>
          </nav>
          <LandingNavControls />
        </div>
      </header>

      <section className="relative flex-1 bg-navy overflow-visible">
        <Image
          src="/landing/hero/mt09sp-coastal-hero.png"
          alt="Yamaha MT-09 SP leaning into a coastal mountain road at sunset"
          fill
          priority
          sizes="100vw"
          className="object-cover"
        />
        <div
          className="absolute inset-0"
          style={{
            background:
              "linear-gradient(90deg, rgba(10,14,26,0.96) 0%, rgba(10,14,26,0.88) 35%, rgba(10,14,26,0.62) 65%, rgba(10,14,26,0.4) 100%)",
          }}
        />
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
              Keep it running. Keep riding.
            </h1>
            <p className="text-white/70 text-lg max-w-xl mb-8">
              Maintenance guidance, service tracking and everyday troubleshooting built around
              the exact motorcycle you ride. Grounded in verified, bike-specific knowledge, not a
              generic guess at your model.
            </p>
            <div className="flex flex-wrap gap-3 mb-10">
              <Link
                href="/chat"
                className="rounded-lg bg-accent px-5 py-3 text-sm font-semibold text-white hover:bg-accent-hover transition-colors focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white"
              >
                Choose your bike →
              </Link>
              <Link
                href="/garage"
                className="rounded-lg bg-white px-5 py-3 text-sm font-semibold text-navy hover:bg-white/90 transition-colors focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white"
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
            <h2 className="text-white font-semibold mb-1">Choose your bike</h2>
            <p className="text-white/50 text-sm mb-5">Manufacturer, model and year, then just ask.</p>
            <LandingPicker />
          </div>
        </div>
      </section>

      <section id="features" className="bg-background py-16 scroll-mt-16">
        <div className="mx-auto max-w-7xl px-4 sm:px-6">
          <p className="text-xs font-semibold tracking-[0.2em] text-accent mb-3">FEATURES</p>
          <h2 className="text-2xl sm:text-3xl font-bold mb-10">Everything tracked against your actual bike</h2>
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-6">
            <FeatureCard
              title="A real service history"
              desc="Log an oil change, a chain adjustment, or a new set of tires just by mentioning it in conversation. It lands in your garage automatically."
            />
            <FeatureCard
              title="Status you can act on"
              desc="Every tracked service shows where it stands: fine, due soon, overdue, or genuinely unknown, not a guess dressed up as certainty."
            />
            <FeatureCard
              title="Specs for your exact bike"
              desc="A 2021 and a 2023 of the same model can differ. Repair only ever answers from knowledge verified for your specific year and variant."
            />
          </div>
        </div>
      </section>

      <section id="how-it-works" className="border-t border-border bg-panel py-16 scroll-mt-16">
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
              desc="Oil changes, chain slack, tire pressure, or a symptom that's bothering you. Repair already knows which bike you mean."
            />
            <Step
              n="3"
              title="Get a grounded answer"
              desc="Explained conversationally, backed by your bike's exact specifications, never a different model or a guess."
            />
          </div>
        </div>
      </section>

      <StoryRow
        eyebrow="FITMENT"
        title="The bike you actually have"
        desc="A model name isn't enough. The same motorcycle can carry different engines, brakes, or service intervals across trims and years. Repair keeps every answer scoped to the exact variant you picked, and says so plainly when something isn't covered yet."
        image="/landing/sections/fitment-mt09sp-workshop.png"
        imageAlt="A Yamaha MT-09 SP parked in a home workshop, surrounded by tools"
      />
      <StoryRow
        eyebrow="OWNERSHIP"
        title="Maintenance that remembers itself"
        desc="Mention an oil change mid-conversation and it's saved. Ask what's due and Repair checks it against your bike's real recorded history, not a generic interval chart. Your garage dashboard shows exactly where every tracked service stands."
        image="/landing/sections/ownership-service-inspection.png"
        imageAlt="A rider reviewing a paper maintenance checklist beside a Yamaha MT-09 SP in a workshop"
        reverse
      />
      <StoryRow
        eyebrow="PREFERENCES"
        title="For every kind of rider"
        desc="Commuting, touring, or off-road, the right tire pressure or suspension setting isn't always the factory default. Tell Repair how you ride and it remembers that alongside your bike's verified baseline specs."
        image="/landing/sections/preferences-alpine-overlook.png"
        imageAlt="A rider and a Yamaha MT-09 SP stopped at a mountain overlook above a lake"
      />

      <section id="from-the-garage" className="border-t border-border bg-background py-16 scroll-mt-16">
        <div className="mx-auto max-w-7xl px-4 sm:px-6">
          <p className="text-xs font-semibold tracking-[0.2em] text-accent mb-3">FROM THE GARAGE</p>
          <h2 className="text-2xl sm:text-3xl font-bold mb-3">Notes on keeping a bike running well</h2>
          <p className="text-muted mb-10 max-w-2xl">
            A few short notes on the kind of maintenance that keeps a bike running well.
          </p>
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-6">
            <GarageCard
              image="/landing/cards/garage-chain-slack.png"
              imageAlt="A mechanic checking the chain slack on a Yamaha MT-09 SP's rear wheel"
              title="Chain slack, done right"
              desc="Too tight wears the sprockets. Too loose risks the chain coming off. A quick guide to checking it properly, not just eyeballing it."
            />
            <GarageCard
              image="/landing/cards/garage-service-schedule.png"
              imageAlt="A handwritten maintenance checklist on a workbench in front of a Yamaha MT-09 SP"
              title="Reading a service schedule without the jargon"
              desc="Manufacturer manuals bury simple intervals in dense tables. Here's what matters when deciding what's next."
            />
            <GarageCard
              image="/landing/cards/garage-due-soon.png"
              imageAlt="A mechanic wiping down the rear wheel of a Yamaha MT-09 SP in a workshop"
              title="What 'due soon' actually means"
              desc="Distance-based and time-based intervals don't always agree. A short explanation of how Repair reconciles the two."
            />
          </div>
        </div>
      </section>

      <footer className="border-t border-border py-8">
        <div className="mx-auto max-w-7xl px-4 sm:px-6 flex flex-col sm:flex-row items-center justify-between gap-4 text-sm text-muted">
          <RepairLogo />
          <nav className="flex items-center gap-4">
            <a href="#features" className="hover:text-foreground">
              Features
            </a>
            <a href="#how-it-works" className="hover:text-foreground">
              How it works
            </a>
          </nav>
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

function FeatureCard({ title, desc }: { title: string; desc: string }) {
  return (
    <div className="rounded-xl border border-border bg-panel p-6">
      <p className="font-semibold mb-1.5">{title}</p>
      <p className="text-sm text-muted leading-relaxed">{desc}</p>
    </div>
  );
}

function Step({ n, title, desc }: { n: string; title: string; desc: string }) {
  return (
    <div className="rounded-xl border border-border bg-background p-6">
      <span className="flex h-8 w-8 items-center justify-center rounded-full bg-navy text-white text-sm font-semibold mb-4">
        {n}
      </span>
      <p className="font-semibold mb-1.5">{title}</p>
      <p className="text-sm text-muted leading-relaxed">{desc}</p>
    </div>
  );
}

function StoryRow({
  eyebrow,
  title,
  desc,
  image,
  imageAlt,
  reverse = false,
}: {
  eyebrow: string;
  title: string;
  desc: string;
  image: string;
  imageAlt: string;
  reverse?: boolean;
}) {
  return (
    <section className="border-t border-border bg-panel py-14">
      <div
        className={`mx-auto max-w-7xl px-4 sm:px-6 grid grid-cols-1 lg:grid-cols-2 gap-8 items-center ${
          reverse ? "lg:[&>*:first-child]:order-2" : ""
        }`}
      >
        <div className="relative rounded-2xl overflow-hidden aspect-[4/3] bg-navy">
          <Image
            src={image}
            alt={imageAlt}
            fill
            sizes="(min-width: 1024px) 50vw, 100vw"
            className="object-cover"
          />
        </div>
        <div>
          <p className="text-xs font-semibold tracking-[0.2em] text-accent mb-3">{eyebrow}</p>
          <h3 className="text-2xl sm:text-3xl font-bold mb-4">{title}</h3>
          <p className="text-muted leading-relaxed max-w-lg">{desc}</p>
        </div>
      </div>
    </section>
  );
}

function GarageCard({
  image,
  imageAlt,
  title,
  desc,
}: {
  image: string;
  imageAlt: string;
  title: string;
  desc: string;
}) {
  return (
    <article className="rounded-xl border border-border bg-panel overflow-hidden">
      <div className="relative h-36 bg-navy-2">
        <Image src={image} alt={imageAlt} fill sizes="(min-width: 640px) 33vw, 100vw" className="object-cover" />
      </div>
      <div className="p-5">
        <p className="font-semibold mb-1.5">{title}</p>
        <p className="text-sm text-muted leading-relaxed">{desc}</p>
      </div>
    </article>
  );
}
