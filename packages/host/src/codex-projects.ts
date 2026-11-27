import { open, readFile, realpath, stat } from "node:fs/promises";
import { homedir } from "node:os";
import { dirname, isAbsolute, join, resolve } from "node:path";
import { DatabaseSync } from "node:sqlite";
import { parse } from "smol-toml";
import type { AgentSessionSummary } from "@orbis/protocol";

type Project = NonNullable<AgentSessionSummary["codexProject"]>;
type Placement = { codexProject: Project } | { codexSection: "projectless" | "other" };
const other = { codexSection: "other" } as const;
const noProject = { codexSection: "projectless" } as const;

const record = (value: unknown): Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value) ? value as Record<string, unknown> : {};

export function codexProjectId(value: unknown): string | undefined {
  return typeof value === "string" && value.trim().length > 0 && value.length <= 256 ? value : undefined;
}

function normalizedPath(path: string): string {
  return path.replace(/^\\\\\?\\UNC\\/iu, "\\\\").replace(/^\\\\\?\\/u, "").replace(/\\/gu, "/").replace(/\/+$/u, "");
}

function rootKey(path: string): string {
  const normalized = normalizedPath(path);
  return /^[a-z]:(?:\/|$)|^\/\//iu.test(normalized) ? normalized.toLowerCase() : normalized;
}

type ProjectSection = {
  project: Project;
  include: Set<string>;
};

function paths(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((path): path is string =>
    typeof path === "string" && path.length > 0 && path.length <= 4096) : [];
}

function desktopRecords(state: Record<string, unknown>, key: string): Record<string, unknown>[] {
  return Object.entries(state).filter(([name]) => name === key || name.startsWith(`electron-owner-value:${key}:`))
    .map(([, value]) => record(value));
}

function containsPath(root: string, cwd: string): boolean {
  return cwd === root || cwd.startsWith(root.endsWith("/") ? root : `${root}/`);
}

async function projectlessDirectory(home: string, userHome: string): Promise<string> {
  const configured = await readFile(join(home, "config.toml"), "utf8")
    .then(text => record(parse(text).desktop).projectlessWorkspaceRoot)
    .catch(() => undefined);
  const root = typeof configured === "string" && isAbsolute(configured) ? configured : join(userHome, "Documents", "Codex");
  return rootKey(await realpath(root).catch(() => root));
}

async function readGitPath(file: string): Promise<string> {
  const handle = await open(file, "r");
  try {
    const bytes = Buffer.alloc(4097);
    const { bytesRead } = await handle.read(bytes);
    return bytesRead > 4096 ? "" : bytes.toString("utf8", 0, bytesRead).trim();
  } finally {
    await handle.close();
  }
}

/** Read linked-worktree metadata without executing repository code or mutating Git state. */
function gitIdentities() {
  type Identity = { common: string; linked: boolean };
  const cache = new Map<string, Promise<Identity | undefined>>();
  function lookup(cwd: string): Promise<Identity | undefined> {
    const path = resolve(cwd);
    let pending = cache.get(path);
    if (!pending) {
      pending = (async () => {
        const dotGit = join(path, ".git");
        const info = await stat(dotGit).catch(() => undefined);
        if (info?.isDirectory()) return { common: rootKey(await realpath(dotGit)), linked: false };
        if (info?.isFile()) {
          const location = /^gitdir: (.+)$/u.exec(await readGitPath(dotGit))?.[1];
          if (!location) return undefined;
          const gitDir = resolve(path, location);
          const commonDir = await readGitPath(join(gitDir, "commondir")).catch(() => "");
          return { common: rootKey(await realpath(resolve(gitDir, commonDir))), linked: commonDir !== "" };
        }
        const parent = dirname(path);
        return parent === path ? undefined : lookup(parent);
      })().catch(() => undefined);
      cache.set(path, pending);
    }
    return pending;
  }
  return (cwd: string) => isAbsolute(cwd) ? lookup(cwd) : Promise.resolve(undefined);
}

async function readDesktopProjectState(home: string): Promise<Record<string, unknown>> {
  const file = join(home, ".codex-global-state.sqlite");
  try {
    await stat(file);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== "ENOENT") return {};
    // Desktop versions before the SQLite settings migration only write this file.
    return readFile(join(home, ".codex-global-state.json"), "utf8")
      .then(text => record(JSON.parse(text)))
      .catch(() => ({}));
  }
  try {
    const db = new DatabaseSync(file, { readOnly: true });
    try {
      const rows = db.prepare(`SELECT key, value FROM settings WHERE scope = 'global' AND (
        key IN ('local-projects', 'thread-project-assignments', 'thread-project-membership-host-ids',
                'projectless-thread-ids', 'app-server-project-id-by-legacy-project-id-by-host',
                'thread-workspace-root-hints')
        OR key GLOB 'electron-owner-value:thread-workspace-root-hints:*')`).all();
      return Object.fromEntries(rows.flatMap(({ key, value }) => {
        if (typeof key !== "string" || typeof value !== "string") return [];
        try { return [[key, JSON.parse(value) as unknown]]; } catch { return []; }
      }));
    } finally {
      db.close();
    }
  } catch {
    // Never resurrect stale JSON assignments after Desktop migrates.
    return {};
  }
}

/** Build local Desktop project sections before projecting membership into the catalog. */
export async function readCodexDesktopProjects(home: string, userHome = homedir()): Promise<(threadId: string, cwd: string) => Promise<Placement>> {
  const state = await readDesktopProjectState(home);
  const savedProjects = record(state["local-projects"]);
  const assignments = record(state["thread-project-assignments"]);
  const hosts = record(state["thread-project-membership-host-ids"]);
  const projectless = new Set(Array.isArray(state["projectless-thread-ids"]) ? state["projectless-thread-ids"] : []);
  // Codex uses host-scoped migration keys. Resolve only unambiguous local aliases.
  const aliases = Object.entries(record(state["app-server-project-id-by-legacy-project-id-by-host"]))
    .filter(([host]) => host.startsWith("local:")).map(([, value]) => record(value));
  const projectlessRoot = await projectlessDirectory(home, userHome);
  const gitIdentity = gitIdentities();
  const workspaceHints = new Map<string, string | null>();
  for (const hints of desktopRecords(state, "thread-workspace-root-hints")) {
    for (const [id, value] of Object.entries(hints)) {
      if (typeof value !== "string" || !value) continue;
      const key = rootKey(value);
      workspaceHints.set(id, !workspaceHints.has(id) || workspaceHints.get(id) === key ? key : null);
    }
  }
  const sections = new Map<string, ProjectSection>();
  const byRoot = new Map<string, ProjectSection | null>();
  function addRoot(path: string, section: ProjectSection) {
    const key = rootKey(path);
    if (!key || key === "~") return;
    if (!byRoot.has(key)) byRoot.set(key, section);
    else if (byRoot.get(key) !== section) byRoot.set(key, null);
  }
  for (const [id, value] of Object.entries(savedProjects)) {
    const { name: rawName, rootPaths, rootPathAliases, pathAlias } = record(value);
    const name = typeof rawName === "string" ? rawName.trim() : "";
    const roots = paths(rootPaths);
    const cwd = roots[0];
    if (codexProjectId(id) === undefined || !name || name.length > 256 || cwd === undefined) continue;
    const ids = new Set(aliases.map(value => codexProjectId(value[id])).filter(value => value !== undefined));
    const project = { projectId: ids.size === 1 ? [...ids][0]! : id, name, cwd };
    const aliasPaths = Array.isArray(rootPathAliases) ? rootPathAliases.map(alias => record(alias).alias) : [];
    const section: ProjectSection = { project, include: new Set() };
    sections.set(id, section);
    for (const path of [...roots, ...paths([...aliasPaths, pathAlias])]) addRoot(path, section);
  }
  await Promise.all([...byRoot].map(async ([path, section]) => {
    if (!section || !isAbsolute(path)) return;
    const canonical = await realpath(path).catch(() => undefined);
    if (canonical) addRoot(canonical, section);
  }));
  for (const [id, hint] of workspaceHints) {
    if (hint !== null && !Object.hasOwn(assignments, id)) byRoot.get(hint)?.include.add(id);
  }
  const orderedRoots = [...byRoot.entries()].sort(([a], [b]) => b.length - a.length);
  let repoSections: Promise<Map<string, Set<ProjectSection>>> | undefined;
  const indexRepositories = async () => {
    const index = new Map<string, Set<ProjectSection>>();
    for (const [root, section] of orderedRoots) {
      if (!section) continue;
      const identity = await gitIdentity(root);
      if (!identity) continue;
      const candidates = index.get(identity.common) ?? new Set<ProjectSection>();
      candidates.add(section);
      index.set(identity.common, candidates);
    }
    return index;
  };
  const projectOf = (section: ProjectSection | null | undefined): Placement => section ? { codexProject: section.project } : other;
  return async (threadId, cwd) => {
    if (hosts[threadId] !== undefined && hosts[threadId] !== "local") return other;
    if (Object.hasOwn(assignments, threadId)) {
      const assignment = record(assignments[threadId]);
      const id = codexProjectId(assignment.projectId);
      return assignment.projectKind === "local" && id !== undefined ? projectOf(sections.get(id)) : other;
    }
    const key = rootKey(cwd);
    if (projectless.has(threadId) || key === "~" || (key !== projectlessRoot && containsPath(projectlessRoot, key))) return noProject;
    const hintedRoot = workspaceHints.get(threadId);
    const hinted = hintedRoot ? byRoot.get(hintedRoot) : undefined;
    if (hinted?.include.has(threadId)) return projectOf(hinted);
    const match = orderedRoots.find(([root]) => containsPath(root, key));
    if (match) return projectOf(match[1]);
    // A linked worktree can be outside every registered path. Match the shared Git directory,
    // never the basename or remote URL, which could also identify an unrelated clone.
    if (orderedRoots.length === 0 || !isAbsolute(cwd)) return other;
    const repository = await gitIdentity(cwd);
    if (!repository?.linked) return other;
    const candidates = (await (repoSections ??= indexRepositories())).get(repository.common);
    return candidates?.size === 1 ? projectOf(candidates.values().next().value) : other;
  };
}
