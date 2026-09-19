import Image from "next/image";

/** The circular assistant avatar shown next to every Repair chat
 * response — one place so a future mark change only happens here.
 * apps/web/public/brand/repair-logo-2.png is a solid black square with
 * the white chain-link mark baked in (not transparent), so it's laid
 * out with `object-cover` inside an already-black, overflow-hidden
 * circle: the square's corners get clipped away by the circular mask,
 * its own black background blends seamlessly with the circle's, and the
 * white mark ends up centered with no visible edges or letterboxing. */
export function AssistantAvatar({ size = 32, className = "" }: { size?: number; className?: string }) {
  return (
    <div
      className={`flex items-center justify-center rounded-full bg-navy overflow-hidden shrink-0 ${className}`}
      style={{ width: size, height: size }}
    >
      <Image
        src="/brand/repair-logo-2.png"
        alt=""
        width={size}
        height={size}
        className="h-full w-full object-cover scale-110"
      />
    </div>
  );
}
