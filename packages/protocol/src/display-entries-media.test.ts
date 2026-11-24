import { describe, expect, it } from "vitest";
import { projectDisplayEntries } from "./display-entries.js";
import type { RemoteSessionEntry } from "./index.js";

const entry = (content: unknown): RemoteSessionEntry => ({
  entryId: "media",
  parentId: null,
  type: "message",
  timestamp: "2026-11-23T00:00:00Z",
  data: { message: { role: "user", content } },
});

describe("historical media projection", () => {
  it("keeps local images and attachments available for Host file references", () => {
    const message = projectDisplayEntries([entry([
      { type: "text", text: "看图" },
      { type: "localImage", path: "C:\\work\\shot.png" },
      { type: "attachment", path: "C:\\work\\report.pdf" },
    ])], "media").entries[0]?.message;
    expect(message?.content).toEqual([
      { type: "text", text: "看图" },
      { type: "render", renderKind: "image", text: "shot.png", sourcePath: "C:\\work\\shot.png" },
      { type: "render", renderKind: "attachment", text: "report.pdf", sourcePath: "C:\\work\\report.pdf" },
    ]);
  });

  it("retains the imageView path and a resolved tool file in canonical history", () => {
    const file = { fileName: "shot.png", referenceId: `orbis-message-file:${"a".repeat(64)}` };
    const tool = { type: "tool_call", toolCallId: "view", toolName: "查看图片" };
    const blocks = [{ ...tool, sourcePath: "shots/shot.png" }, { ...tool, file }];
    const row = entry(blocks);
    row.data.message = { role: "assistant", content: blocks };
    expect(projectDisplayEntries([row], "media").entries[0]?.message?.content).toEqual(blocks);
  });

  it("keeps remote image URLs renderable after history reload", () => {
    const message = projectDisplayEntries([entry([
      { type: "image", url: "https://example.test/shot.png" },
      { type: "image", image_url: "data:image/png;base64,AAAA" },
    ])], "media").entries[0]?.message;
    expect(message?.content).toEqual([
      { type: "render", renderKind: "image", text: "图片", imageUrl: "https://example.test/shot.png" },
      { type: "render", renderKind: "image", text: "图片", imageUrl: "data:image/png;base64,AAAA" },
    ]);
  });
});
