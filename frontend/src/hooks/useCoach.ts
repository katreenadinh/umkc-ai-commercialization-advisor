import { useEffect, useMemo, useRef, useState } from "react";
import {
  createDraft,
  exportSummary,
  forgetDraftId,
  generateSummary,
  getDraft,
  savedDraftId,
  sendInterview,
  skipField,
} from "../api";
import type { DraftView, Msg } from "../types";
import { errorMessage } from "../utils/errors";
import { fieldDepth } from "../utils/fields";
import { fromTranscript, paragraphsFrom } from "../utils/messages";
import { suggestionChips } from "../utils/suggestions";

/**
 * All state and actions for the disclosure coach conversation.
 * `showCoach` switches the app to the coach page (used when a scenario or question starts a turn).
 */
export function useCoach(showCoach: () => void) {
  const [messages, setMessages] = useState<Msg[]>([]);
  const [draft, setDraft] = useState<DraftView | null>(null);
  const [compose, setCompose] = useState("");
  const [copied, setCopied] = useState(false);
  const [pending, setPending] = useState(false);
  const [bootError, setBootError] = useState("");
  const boxRef = useRef<HTMLTextAreaElement>(null);
  const draftIdRef = useRef<string | null>(null);
  const pendingRef = useRef(false);
  const askedFieldRef = useRef<string[]>([]);

  useEffect(() => {
    pendingRef.current = pending;
  }, [pending]);

  useEffect(() => {
    draftIdRef.current = draft?.id ?? null;
  }, [draft]);

  useEffect(() => {
    void boot();
  }, []);

  const items = draft?.fields ?? [];
  const started = items.filter((item) => fieldDepth(item) !== "empty").length;
  const usable = items.filter((item) => fieldDepth(item) === "usable").length;
  const lastAdvisorId = [...messages].reverse().find((msg) => msg.role === "advisor")?.id;
  const currentAsk = askedFieldRef.current[0];
  const currentField = items.find((field) => field.id === currentAsk);
  const placeholder = currentField?.label
    ? `Tell me about ${currentField.label.toLowerCase()}, or skip this note.`
    : "Ask a question, or go back to any note on the right.";

  async function boot() {
    setPending(true);
    pendingRef.current = true;
    try {
      const saved = savedDraftId();
      if (saved) {
        try {
          const existing = await getDraft(saved);
          applyDraft(existing, fromTranscript(existing));
          askedFieldRef.current = existing.transcript
            .filter((turn) => turn.role === "ASSISTANT")
            .at(-1)?.fieldIds ?? [];
          return;
        } catch {
          forgetDraftId();
        }
      }
      await startCoach();
    } catch (err) {
      setBootError(errorMessage(err, "Could not reach the advisor API."));
    } finally {
      pendingRef.current = false;
      setPending(false);
    }
  }

  function applyDraft(next: DraftView, nextMessages?: Msg[]) {
    setDraft(next);
    draftIdRef.current = next.id;
    if (nextMessages) setMessages(nextMessages);
  }

  async function refreshDraft(id: string) {
    const next = await getDraft(id);
    setDraft(next);
    return next;
  }

  async function startCoach() {
    const created = await createDraft();
    askedFieldRef.current = created.opening.askedFieldIds;
    applyDraft(created.draft, [
      {
        id: `${created.draft.id}-open`,
        role: "advisor",
        paragraphs: paragraphsFrom(created.opening.assistantMessage),
      },
    ]);
    setCompose("");
    setBootError("");
    showCoach();
    boxRef.current?.focus();
    return created.draft.id;
  }

  function openCoach() {
    showCoach();
    boxRef.current?.focus();
  }

  async function reset() {
    forgetDraftId();
    setPending(true);
    pendingRef.current = true;
    try {
      await startCoach();
    } catch (err) {
      setBootError(errorMessage(err, "Could not start a new draft."));
    } finally {
      pendingRef.current = false;
      setPending(false);
    }
  }

  async function ask(text: string) {
    const clean = text.trim();
    const id = draftIdRef.current;
    if (!clean || !id || pendingRef.current) return;
    setCompose("");
    showCoach();
    pendingRef.current = true;
    setPending(true);
    const userMsg: Msg = { id: `${Date.now()}-u`, role: "user", text: clean };
    setMessages((curr) => [...curr, userMsg]);
    try {
      const reply = await sendInterview(id, clean);
      askedFieldRef.current = reply.askedFieldIds;
      await refreshDraft(id);
      setMessages((curr) => [
        ...curr,
        {
          id: `${Date.now()}-a`,
          role: "advisor",
          paragraphs: paragraphsFrom(reply.assistantMessage),
        },
      ]);
    } catch (err) {
      setMessages((curr) => [
        ...curr,
        {
          id: `${Date.now()}-err`,
          role: "advisor",
          paragraphs: [errorMessage(err, "The assistant is temporarily unavailable.")],
        },
      ]);
    } finally {
      pendingRef.current = false;
      setPending(false);
    }
  }

  async function skipCurrent() {
    const id = draftIdRef.current;
    const fieldId = askedFieldRef.current[0];
    if (!id || !fieldId || pendingRef.current) return;
    pendingRef.current = true;
    setPending(true);
    setMessages((curr) => [...curr, { id: `${Date.now()}-u`, role: "user", text: "Skip this for now." }]);
    try {
      const reply = await skipField(id, fieldId);
      askedFieldRef.current = reply.askedFieldIds;
      await refreshDraft(id);
      setMessages((curr) => [
        ...curr,
        { id: `${Date.now()}-a`, role: "advisor", paragraphs: paragraphsFrom(reply.assistantMessage) },
      ]);
    } catch (err) {
      setMessages((curr) => [
        ...curr,
        {
          id: `${Date.now()}-err`,
          role: "advisor",
          paragraphs: [errorMessage(err, "Could not skip that note.")],
        },
      ]);
    } finally {
      pendingRef.current = false;
      setPending(false);
    }
  }

  /** Starts a fresh draft and sends a scenario prompt as the first message. */
  function runScenario(text: string) {
    void (async () => {
      try {
        forgetDraftId();
        await startCoach();
        pendingRef.current = false;
        await ask(text);
      } catch (err) {
        setBootError(errorMessage(err, "Could not start that scenario."));
        pendingRef.current = false;
        setPending(false);
      }
    })();
  }

  async function copyBrief() {
    const id = draftIdRef.current;
    if (!id) return;
    const markdown = await generateSummary(id);
    await navigator.clipboard.writeText(markdown);
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1800);
  }

  async function downloadBrief() {
    const id = draftIdRef.current;
    if (!id) return;
    await generateSummary(id);
    await exportSummary(id, "docx");
  }

  function copyNotes() {
    void copyBrief().catch((err: unknown) => setBootError(errorMessage(err, "Could not copy notes.")));
  }

  function downloadNotes() {
    void downloadBrief().catch((err: unknown) => setBootError(errorMessage(err, "Could not download the summary.")));
  }

  const lastUserText = [...messages].reverse().find((msg) => msg.role === "user")?.text || "";
  const lastAdvisorText = [...messages].reverse().find((msg) => msg.role === "advisor")?.paragraphs?.join(" ") || "";
  const suggestions = useMemo(
    () => suggestionChips(currentField, lastUserText, lastAdvisorText, messages.length),
    [currentField, lastUserText, lastAdvisorText, messages.length],
  );

  return {
    messages,
    draft,
    items,
    started,
    usable,
    lastAdvisorId,
    placeholder,
    suggestions,
    compose,
    setCompose,
    copied,
    pending,
    bootError,
    boxRef,
    ask,
    skipCurrent,
    reset,
    runScenario,
    copyNotes,
    downloadNotes,
    openCoach,
  };
}

export type CoachState = ReturnType<typeof useCoach>;
