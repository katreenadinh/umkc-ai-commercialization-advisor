# Frontend

React + TypeScript + Vite UI for the Commercialization Advisor. `npm run dev` serves it (the Vite dev server proxies `/api` to the Java API); `npm run build` type-checks and builds to `dist/`.

## Layout of `src/`

| Folder | What lives there |
| --- | --- |
| `main.tsx` | Mounts `<App />` and imports `styles.css`. |
| `App.tsx` | Thin shell: current page, `useCoach`, `useContact`, the header and the page switch. |
| `types/` | `api.ts` mirrors backend response shapes (`DraftView`, `FieldView`, `Reply`, `SourceView`, `ContactInfo`, ...). `app.ts` holds UI-only types (`Page`, `Msg`, `FieldDepth`). Import from `types` (barrel). |
| `api/` | Everything that talks to the backend. `client.ts` (owner token, headers, `read` JSON/error helper), `draftStorage.ts` (draft id in sessionStorage), `drafts.ts`, `sources.ts`, `contact.ts`. Import from `api` (barrel). |
| `data/` | Static content: `scenarios.ts` (faculty scenarios page), `chips.ts` (suggested-reply pools), `contact.ts` (`FALLBACK_CONTACT` used if `/api/contact` fails). |
| `utils/` | Pure helpers with no React: `fields.ts` (note depth/status), `messages.ts` (advisor text to paragraphs, transcript to messages), `suggestions.ts` (chip rotation), `sources.ts` (file kind, official URL check, sort order), `errors.ts` (`errorMessage(err, fallback)`). |
| `hooks/` | `useCoach.ts` owns all coach conversation state and actions (boot, ask, skip, reset, scenarios, copy/download notes); `CoachState` is its return type and is passed to coach components. `useContact.ts`, `useSources.ts`. |
| `components/` | One folder per page: `layout/AppHeader`, `coach/` (`CoachPage`, `MessageThread`, `MessageBubble`, `NotesPanel`, `ContactPanel`, `Composer`), `scenarios/ScenarioPage`, `sources/` (`SourcePage`, `SourceViewer` modal with PDF/Word/text views), `deploy/DeployPage`. |
| `styles.css` | All styles, one global sheet. Class names are shared across components. |

## Where to make common changes

- **Add a nav page:** add the id to `Page` in `types/app.ts`, add it to `NAV` in `components/layout/AppHeader.tsx`, create `components/<page>/`, and add a branch to the switch in `App.tsx`.
- **Add or change a backend call:** add the function in the matching `api/*.ts` file (use `headers()` and `read()` from `api/client.ts`), and its response type in `types/api.ts`.
- **Change coach behavior** (what happens on send, skip, start over, scenario run): `hooks/useCoach.ts`.
- **Change suggested replies:** pools in `data/chips.ts`, selection logic in `utils/suggestions.ts`.
- **Change note status labels / "enough to discuss" threshold:** `utils/fields.ts`.
- **Edit a scenario:** `data/scenarios.ts`.
- **Change the office contact fallback:** `data/contact.ts` (the live value comes from `/api/contact`; the panel itself is `components/coach/ContactPanel.tsx`).
- **Change how sources are sorted or opened:** `utils/sources.ts` and `components/sources/SourceViewer.tsx`.
- **Styling:** `styles.css`.
