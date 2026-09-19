import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import { AuthProvider } from "@/lib/AuthProvider";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

const description =
  "Know your bike. Ride more. Maintenance guidance, service tracking and everyday troubleshooting built around the exact motorcycle you ride, grounded in verified, bike-specific knowledge.";

export const metadata: Metadata = {
  title: "Repair — Know your bike. Ride more.",
  description,
  openGraph: {
    title: "Repair — Know your bike. Ride more.",
    description,
    type: "website",
    siteName: "Repair",
  },
  twitter: {
    card: "summary",
    title: "Repair — Know your bike. Ride more.",
    description,
  },
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html
      lang="en"
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}
    >
      <body className="min-h-full flex flex-col">
        <AuthProvider>{children}</AuthProvider>
      </body>
    </html>
  );
}
