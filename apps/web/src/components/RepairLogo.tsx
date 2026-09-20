import Image from "next/image";
import Link from "next/link";

/** The one place Repair's logo mark and wordmark are defined — see
 * apps/web/public/brand/repair-logo.png (supplied as-is, never redrawn or
 * recolored here). Used in the public landing navbar, the authenticated
 * app's sidebar, and the footer, so a future logo change only happens in
 * this one file. A single compact lockup: mark + "Repair", nothing
 * underneath — the brand slogan lives only in the landing hero now.
 *
 * The mark itself is solid black on a transparent background. On a light
 * page it's used directly; on a dark (navy) surface it needs a small
 * white chip behind it to stay visible — `dark` controls that backing
 * and the wordmark color, it never changes the image itself.
 *
 * The image's alt is left empty: the adjacent "Repair" text is already
 * visible and conveys the name, so a non-empty alt would just have a
 * screen reader announce "Repair" twice. */
export function RepairLogo({
  dark = false,
  size = 32,
  className = "",
}: {
  dark?: boolean;
  size?: number;
  className?: string;
}) {
  const mark = (
    <Image
      src="/brand/repair-logo.png"
      alt=""
      width={size}
      height={size}
      className="object-contain"
      style={{ width: size, height: size }}
      priority
    />
  );

  return (
    <Link href="/" className={`flex items-center gap-2.5 shrink-0 ${className}`}>
      {dark ? (
        <span
          className="flex items-center justify-center rounded-lg bg-white shrink-0 p-1"
          style={{ width: size + 6, height: size + 6 }}
        >
          {mark}
        </span>
      ) : (
        mark
      )}
      <span
        className={`font-bold text-xl leading-none tracking-tight ${dark ? "text-white" : "text-navy"}`}
      >
        Repair
      </span>
    </Link>
  );
}
