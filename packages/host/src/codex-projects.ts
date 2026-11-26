import { readFile } from "node:fs/promises";
import { join } from "node:path";
import type { AgentSessionSummary } from "@orbis/protocol";

type Project = NonNullable<AgentSessionSummary["codexProject"]>;

const record = (value: unknown): Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value) ? value as Record<string, unknown> : {};

export function codexProjectId(value: unknown): string | undefined {
  return typeof value === "string" && value.trim().length > 0 && value.length <= 256 ? value : undefined;
}

/** Desktop can still store explicit local assignments outside app-server's thread records. */
export async function readCodexDesktopProjects(home: string): Promise<(threadId: string) => Project | undefined> {
  let state: Record<string, unknown>;
  try {
    state = record(JSON.parse(await readFile(join(home, ".codex-global-state.json"), "utf8")));
  } catch {
    return () => undefined;
  }
  const projects = record(state["local-projects"]);
  const assignments = record(state["thread-project-assignments"]);
  const hosts = record(state["thread-project-membership-host-ids"]);
  const projectless = new Set(Array.isArray(state["projectless-thread-ids"]) ? state["projectless-thread-ids"] : []);
  // Codex uses host-scoped migration keys. Resolve only unambiguous local aliases.
  const aliases = Object.entries(record(state["app-server-project-id-by-legacy-project-id-by-host"]))
    .filter(([host]) => host.startsWith("local:")).map(([, value]) => record(value));
  return threadId => {
    if (projectless.has(threadId) || (hosts[threadId] !== undefined && hosts[threadId] !== "local")) return undefined;
    const assignment = record(assignments[threadId]);
    const id = codexProjectId(assignment.projectId);
    if (assignment.projectKind !== "local" || id === undefined) return undefined;
    const project = record(projects[id]);
    const name = typeof project.name === "string" ? project.name.trim() : "";
    const cwd = Array.isArray(project.rootPaths) ? project.rootPaths.find(
      (path): path is string => typeof path === "string" && path.length > 0 && path.length <= 4096,
    ) : undefined;
    if (!name || name.length > 256 || cwd === undefined) return undefined;
    const ids = new Set(aliases.map(value => codexProjectId(value[id])).filter(value => value !== undefined));
    return { projectId: ids.size === 1 ? [...ids][0]! : id, name, cwd };
  };
}
