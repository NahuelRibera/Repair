import { ChatShell } from "@/components/ChatShell";
import { RequireAuth } from "@/components/RequireAuth";

export default function ChatLayout({ children }: { children: React.ReactNode }) {
  return (
    <RequireAuth>
      <ChatShell>{children}</ChatShell>
    </RequireAuth>
  );
}
