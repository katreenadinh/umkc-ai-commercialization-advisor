import type { Page } from "../../types";

const NAV: [Page, string][] = [
  ["coach", "Disclosure coach"],
  ["scenarios", "Faculty scenarios"],
  ["sources", "Source library"],
  ["deploy", "For the university"],
];

interface Props {
  page: Page;
  onNavigate: (page: Page) => void;
}

export function AppHeader({ page, onNavigate }: Props) {
  return (
    <header className="top">
      <div className="brand">
        <div>
          <p className="eyebrow">Prototype for Hackaroo</p>
          <h1>Commercialization Advisor</h1>
        </div>
      </div>
      <nav>
        {NAV.map(([id, label]) => (
          <button key={id} className={page === id ? "on" : ""} onClick={() => onNavigate(id)}>
            {label}
          </button>
        ))}
      </nav>
    </header>
  );
}
