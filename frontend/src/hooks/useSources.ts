import { useEffect, useMemo, useState } from "react";
import { listSources } from "../api";
import type { SourceView } from "../types";
import { errorMessage } from "../utils/errors";
import { sortSources } from "../utils/sources";

/** Loads the approved source list once and returns it sorted (HTML, Word, PDF, then title). */
export function useSources() {
  const [sources, setSources] = useState<SourceView[]>([]);
  const [error, setError] = useState("");
  const sorted = useMemo(() => sortSources(sources), [sources]);

  useEffect(() => {
    listSources()
      .then(setSources)
      .catch((err: unknown) => setError(errorMessage(err, "Could not load sources.")));
  }, []);

  return { sources, sorted, error };
}
