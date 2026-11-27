import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { join } from "node:path";
import type { AgentSessionSummary } from "@orbis/protocol";

type Project = NonNullable<AgentSessionSummary["codexProject"]>;

const record = (value: unknown): Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value) ? value as Record<string, unknown> : {};

export function codexProjectId(value: unknown): string | undefined {
  return typeof value === "string" && value.trim().length > 0 && value.length <= 256 ? value : undefined;
}

const projectlessOutput = /(?:^|\/)Documents\/Codex\/\d{4}-\d{2}-\d{2}[/-][a-z0-9][a-z0-9-]*(?:\/|$)/iu;

function normalizedPath(path: string): string {
  return path.replace(/^\\\\\?\\UNC\\/iu, "\\\\").replace(/^\\\\\?\\/u, "").replace(/\\/gu, "/").replace(/\/+$/u, "");
}

function rootKey(path: string): string {
  const normalized = normalizedPath(path);
  return /^[a-z]:(?:\/|$)|^\/\//iu.test(normalized) ? normalized.toLowerCase() : normalized;
}

/** Keep Codex membership when known and group ordinary workspace chats by cwd. */
export async function readCodexDesktopProjects(home: string): Promise<(threadId: string, cwd: string) => Project | undefined> {
  const state = await readFile(join(home, ".codex-global-state.json"), "utf8")
    .then(text => record(JSON.parse(text)))
    .catch((): Record<string, unknown> => ({}));
  const savedProjects = record(state["local-projects"]);
  const assignments = record(state["thread-project-assignments"]);
  const hosts = record(state["thread-project-membership-host-ids"]);
  const projectless = new Set(Array.isArray(state["projectless-thread-ids"]) ? state["projectless-thread-ids"] : []);
  // Codex uses host-scoped migration keys. Resolve only unambiguous local aliases.
  const aliases = Object.entries(record(state["app-server-project-id-by-legacy-project-id-by-host"]))
    .filter(([host]) => host.startsWith("local:")).map(([, value]) => record(value));
  const outputHints = Object.entries(state).filter(([key]) => key === "thread-projectless-output-directories" ||
    key.startsWith("electron-owner-value:thread-projectless-output-directories:")).map(([, value]) => record(value));
  const projects = new Map<string, Project>();
  const roots: Array<{ key: string; project: Project }> = [];
  for (const [id, value] of Object.entries(savedProjects)) {
    const { name: rawName, rootPaths } = record(value);
    const name = typeof rawName === "string" ? rawName.trim() : "";
    const paths = Array.isArray(rootPaths) ? rootPaths.filter((path): path is string =>
      typeof path === "string" && path.length > 0 && path.length <= 4096) : [];
    const cwd = paths[0];
    if (codexProjectId(id) === undefined || !name || name.length > 256 || cwd === undefined) continue;
    const ids = new Set(aliases.map(value => codexProjectId(value[id])).filter(value => value !== undefined));
    const project = { projectId: ids.size === 1 ? [...ids][0]! : id, name, cwd };
    projects.set(id, project);
    for (const path of paths) {
      const key = rootKey(path);
      if (key && key !== "~" && !projectlessOutput.test(key)) roots.push({ key, project });
    }
  }
  roots.sort((a, b) => b.key.length - a.key.length);
  return (threadId, cwd) => {
    if (projectless.has(threadId) || (hosts[threadId] !== undefined && hosts[threadId] !== "local")) return undefined;
    const assignment = record(assignments[threadId]);
    if (Object.hasOwn(assignments, threadId)) {
      const id = codexProjectId(assignment.projectId);
      return assignment.projectKind === "local" && id !== undefined ? projects.get(id) : undefined;
    }
    const key = rootKey(cwd);
    if (outputHints.some(hints => typeof hints[threadId] === "string") || projectlessOutput.test(key)) return undefined;
    let matched: (typeof roots)[number] | undefined;
    for (const root of roots) {
      if (matched !== undefined && root.key.length < matched.key.length) break;
      if (key !== root.key && !key.startsWith(`${root.key}/`)) continue;
      if (matched !== undefined && matched.project.projectId !== root.project.projectId) return undefined;
      matched = root;
    }
    if (matched) return matched.project;
    if (!/^(?:[a-z]:\/|\/)/iu.test(key) || cwd.length > 4096) return undefined;
    const path = normalizedPath(cwd);
    const name = (path.slice(path.lastIndexOf("/") + 1) || path).slice(0, 256);
    return { projectId: `cwd:${createHash("sha256").update(key).digest("hex")}`, name, cwd };
  };
}
