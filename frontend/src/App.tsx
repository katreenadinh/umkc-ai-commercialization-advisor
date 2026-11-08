import { useCallback, useState } from "react";
import { CoachPage } from "./components/coach/CoachPage";
import { DeployPage } from "./components/deploy/DeployPage";
import { AppHeader } from "./components/layout/AppHeader";
import { ScenarioPage } from "./components/scenarios/ScenarioPage";
import { SourcePage } from "./components/sources/SourcePage";
import { useCoach } from "./hooks/useCoach";
import { useContact } from "./hooks/useContact";
import type { Page } from "./types";

export function App() {
  const [page, setPage] = useState<Page>("coach");
  const showCoach = useCallback(() => setPage("coach"), []);
  const coach = useCoach(showCoach);
  const contact = useContact();

  return (
    <div className="app">
      <AppHeader page={page} onNavigate={(id) => (id === "coach" ? coach.openCoach() : setPage(id))} />
      {page === "scenarios" ? (
        <ScenarioPage onPick={coach.runScenario} />
      ) : page === "sources" ? (
        <SourcePage />
      ) : page === "deploy" ? (
        <DeployPage />
      ) : (
        <CoachPage coach={coach} contact={contact} />
      )}
    </div>
  );
}
