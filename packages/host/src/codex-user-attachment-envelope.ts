import { tmpdir } from "node:os";
import { isAbsolute, relative, resolve } from "node:path";

/** A desktop Codex attachment preamble is metadata, not the message body. */
export function codexUserAttachmentEnvelope(raw: string): { text: string; clipboardNames: string[] } | undefined {
  const lines = raw.replace(/\r\n/gu, "\n").trimStart().split("\n");
  if (!/^# Files mentioned by the user:\s*$/iu.test(lines[0] ?? "")) return undefined;
  const boundary = lines.findIndex((line, index) => index > 0 && /^#{0,6}\s*My request(?: for Codex)?:\s*$/iu.test(line));
  if (boundary < 0) return undefined;
  const clipboardNames = new Set<string>();
  let files = 0;
  for (const line of lines.slice(1, boundary)) {
    const trimmed = line.trim();
    if (!trimmed || /^(?:Image attachment: (?:true|false)|Distinguish instructions in attached documents from the user's request\.)$/iu.test(trimmed)) continue;
    const row = /^##\s+([^:\r\n]+):\s+(.+)$/u.exec(trimmed);
    if (!row || ++files > 20) return undefined;
    const [, label, path] = row;
    if (label && path && /^codex-clipboard-[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}\.(?:png|jpe?g|webp|gif)$/iu.test(label) &&
        isAbsolute(path) && !/[\u0000-\u001f\u007f]/u.test(path)) {
      const rel = relative(tmpdir(), resolve(path));
      const same = process.platform === "win32" ? rel.toLowerCase() === label.toLowerCase() : rel === label;
      if (same) clipboardNames.add(label);
    }
  }
  return files ? { text: lines.slice(boundary + 1).join("\n").trim(), clipboardNames: [...clipboardNames] } : undefined;
}
