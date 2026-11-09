// Converts the preparation notes' small Markdown subset (headings, bullets, sub-bullets, numbered items,
// **bold**, _italic_) into HTML for pasting into Word, Google Docs, or email, and into clean plain text.

const INLINE = /\*\*(.+?)\*\*|(?<![\w])_(.+?)_(?![\w])/g;

function escapeHtml(text: string): string {
  return text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

function inlineHtml(text: string): string {
  return escapeHtml(text).replace(INLINE, (_, bold, italic) => (bold ? `<strong>${bold}</strong>` : `<em>${italic}</em>`));
}

function inlinePlain(text: string): string {
  return text.replace(INLINE, (_, bold, italic) => bold ?? italic);
}

export function notesToHtml(markdown: string): string {
  const out: string[] = [];
  let list: "ul" | "ol" | null = null;
  let subOpen = false;
  const closeSub = () => {
    if (subOpen) out.push("</ul>");
    subOpen = false;
  };
  const closeList = () => {
    closeSub();
    if (list) out.push(`</li></${list}>`);
    list = null;
  };
  for (const raw of markdown.split("\n")) {
    const line = raw.trimEnd();
    const numbered = /^\d+\.\s+(.*)$/.exec(line);
    if (line.startsWith("  - ") && list) {
      if (!subOpen) out.push("<ul>");
      subOpen = true;
      out.push(`<li>${inlineHtml(line.slice(4))}</li>`);
    } else if (line.startsWith("- ") || numbered) {
      const kind = numbered ? "ol" : "ul";
      closeSub();
      if (list !== kind) {
        closeList();
        out.push(`<${kind}><li>`);
        list = kind;
      } else {
        out.push("</li><li>");
      }
      out.push(inlineHtml(numbered ? numbered[1] : line.slice(2)));
    } else {
      closeList();
      if (!line) continue;
      if (line.startsWith("# ")) out.push(`<h1>${inlineHtml(line.slice(2))}</h1>`);
      else if (line.startsWith("## ")) out.push(`<h2>${inlineHtml(line.slice(3))}</h2>`);
      else out.push(`<p>${inlineHtml(line)}</p>`);
    }
  }
  closeList();
  return `<div style="font-family: Arial, sans-serif; font-size: 11pt;">${out.join("")}</div>`;
}

export function notesToPlain(markdown: string): string {
  return markdown
    .split("\n")
    .map((raw) => {
      const line = raw.trimEnd();
      if (line.startsWith("# ")) return inlinePlain(line.slice(2)).toUpperCase();
      if (line.startsWith("## ")) return inlinePlain(line.slice(3)).toUpperCase();
      if (line.startsWith("  - ")) return `    - ${inlinePlain(line.slice(4))}`;
      if (line.startsWith("- ")) return `• ${inlinePlain(line.slice(2))}`;
      return inlinePlain(line);
    })
    .join("\n");
}
