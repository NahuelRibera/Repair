"use client";

import { useState } from "react";

/** The signed-in rider's avatar: their Google profile picture when one is
 * available, or a clean initials fallback otherwise — no customization,
 * no upload, no settings. Falls back automatically if the picture URL is
 * missing or fails to load, so it's never a broken-image icon. */
export function AccountAvatar({
  displayName,
  email,
  pictureUrl,
  size = 32,
  className = "",
}: {
  displayName: string | null;
  email: string;
  pictureUrl: string | null;
  size?: number;
  className?: string;
}) {
  const [failed, setFailed] = useState(false);
  const initial = (displayName?.trim()?.[0] ?? email.trim()[0] ?? "R").toUpperCase();

  if (!pictureUrl || failed) {
    return (
      <span
        className={`flex items-center justify-center rounded-full bg-navy text-white font-semibold shrink-0 ${className}`}
        style={{ width: size, height: size, fontSize: size * 0.42 }}
        aria-hidden
      >
        {initial}
      </span>
    );
  }

  return (
    // eslint-disable-next-line @next/next/no-img-element
    <img
      src={pictureUrl}
      alt={`${displayName || email}'s profile picture`}
      width={size}
      height={size}
      referrerPolicy="no-referrer"
      className={`rounded-full object-cover shrink-0 ${className}`}
      style={{ width: size, height: size }}
      onError={() => setFailed(true)}
    />
  );
}
