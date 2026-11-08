import type { CoachState } from "../../hooks/useCoach";
import type { ContactInfo } from "../../types";
import { Composer } from "./Composer";
import { ContactPanel } from "./ContactPanel";
import { MessageThread } from "./MessageThread";
import { NotesPanel } from "./NotesPanel";

interface Props {
  coach: CoachState;
  contact: ContactInfo | null;
}

export function CoachPage({ coach, contact }: Props) {
  return (
    <main className="workspace">
      <MessageThread coach={coach} />
      <aside className="rail">
        <NotesPanel coach={coach} />
        {contact && <ContactPanel contact={contact} />}
      </aside>
      <Composer coach={coach} />
    </main>
  );
}
