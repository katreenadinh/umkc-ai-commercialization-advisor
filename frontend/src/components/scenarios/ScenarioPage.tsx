import { SCENARIOS } from "../../data/scenarios";

export function ScenarioPage({ onPick }: { onPick: (text: string) => void }) {
  return (
    <section className="page scenarios-page">
      <header className="page-intro">
        <p className="eyebrow">Demonstration set</p>
        <h2>Realistic faculty situations</h2>
        <p>
          Each one is handled from the mentor materials. The useful outcome is a clearer description and a correct handoff, not a yes on patentability.
        </p>
      </header>
      <div className="scenario-cards">
        {SCENARIOS.map((scenario) => (
          <article key={scenario.id} className="card">
            <p className="eyebrow">{scenario.audience}</p>
            <h3>{scenario.label}</h3>
            <p>{scenario.shows}</p>
            <blockquote>{scenario.prompt}</blockquote>
            <button onClick={() => onPick(scenario.prompt)}>Run this scenario</button>
          </article>
        ))}
      </div>
    </section>
  );
}
