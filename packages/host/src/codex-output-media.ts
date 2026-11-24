import { createHash } from "node:crypto";
import { existsSync, mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { isAbsolute, join } from "node:path";
import type { RemoteContent } from "@orbis/protocol";

const maxImageBytes = 16 * 1024 * 1024;
const maxCacheBytes = 256 * 1024 * 1024;
const maxImages = 4096;
const maxEncodedLength = Math.ceil(maxImageBytes / 3) * 4;

type ImageBlock = Extract<RemoteContent, { type: "render" }>;

export function codexMediaPath(value: unknown): string | undefined {
  if (typeof value !== "string" || value.length === 0 || value.length > 4096 ||
    Array.from(value).some(ch => ch.charCodeAt(0) < 32 || /[\u007f\u202a-\u202e\u2066-\u2069]/u.test(ch)) ||
    (/^[a-z][a-z\d+.-]*:/iu.test(value) && !/^[a-z]:[\\/]/iu.test(value))) return undefined;
  const name = value.split(/[\\/]/u).at(-1)!;
  return name.length > 0 && name.length <= 255 ? value : undefined;
}

/** Native results may carry image bytes instead of a file. Keep the bytes out of all session messages. */
export class CodexOutputMedia {
  #directory: string | undefined;
  #bytes = 0;
  readonly #images = new Map<string, string>();

  constructor(private readonly log?: (message: string) => void) {}

  image(value: unknown, label: string, allowBareBase64 = false): ImageBlock | undefined {
    if (typeof value !== "string" || value.length > maxEncodedLength + 1024) return undefined;
    const source = value.trim();
    if (source.length <= 8192 && /^https?:\/\//iu.test(source)) {
      const url = URL.parse(source);
      if (url && !url.username && !url.password) return { type: "render", renderKind: "image", text: label, imageUrl: url.href };
    }
    const data = /^data:image\/[a-z0-9.+-]+;base64,/iu.exec(source);
    if (!data && !allowBareBase64) return undefined;
    const encoded = source.slice(data?.[0].length ?? 0).replace(/\s/gu, "");
    if (encoded.length > maxEncodedLength || !/^[a-z0-9+/]+={0,2}$/iu.test(encoded) || encoded.length % 4 === 1) return undefined;
    const bytes = Buffer.from(encoded, "base64");
    const format = imageExtension(bytes);
    if (!format || bytes.length > maxImageBytes) return undefined;
    const path = this.#store(bytes, format);
    return path === undefined ? undefined : { type: "render", renderKind: "image", text: `image.${format}`, sourcePath: path };
  }

  generated(item: Record<string, unknown>): { block: ImageBlock; path?: string } {
    const block: ImageBlock = { type: "render", renderKind: "codex_generated_image", text: "生成图片暂不可显示" };
    if (item.status === "failed") return { block: { ...block, text: "图片生成失败" } };
    const saved = codexMediaPath(item.savedPath ?? item.saved_path);
    const path = item.status === "completed" && saved && isAbsolute(saved) ? saved : undefined;
    if (path && existsSync(path)) return { block, path };
    const image = this.image(item.result, "生成图片", true);
    if (image?.sourcePath) return { block, path: image.sourcePath };
    if (image) return { block: image };
    return path ? { block, path } : { block };
  }

  #store(bytes: Buffer, format: string): string | undefined {
    // Content identity keeps the same reference on replay without reusing an old path for changed bytes.
    const key = createHash("sha256").update(bytes).digest("hex");
    const known = this.#images.get(key);
    if (known !== undefined) return known;
    if (this.#images.size >= maxImages || this.#bytes + bytes.length > maxCacheBytes) {
      this.log?.("Codex 图片缓存已达上限，无法保存内联图片");
      return undefined;
    }
    try {
      this.#directory ??= mkdtempSync(join(tmpdir(), "orbis-codex-media-"));
      const dir = join(this.#directory, key);
      mkdirSync(dir, { recursive: true });
      const path = join(dir, `image.${format}`);
      writeFileSync(path, bytes, { mode: 0o600 });
      this.#images.set(key, path);
      this.#bytes += bytes.length;
      return path;
    } catch {
      this.log?.("Codex 内联图片无法写入 Host 缓存");
      return undefined;
    }
  }

  async dispose(): Promise<void> {
    const directory = this.#directory;
    this.#directory = undefined;
    this.#images.clear();
    this.#bytes = 0;
    if (directory) await rm(directory, { recursive: true, force: true }).catch(() => this.log?.("Codex 图片缓存未能清理"));
  }
}

function imageExtension(bytes: Buffer): string | undefined {
  if (bytes.length >= 8 && bytes.subarray(0, 8).equals(Buffer.from("89504e470d0a1a0a", "hex"))) return "png";
  if (bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return "jpg";
  if (["GIF87a", "GIF89a"].includes(bytes.toString("ascii", 0, 6))) return "gif";
  if (bytes.toString("ascii", 0, 4) === "RIFF" && bytes.toString("ascii", 8, 12) === "WEBP") return "webp";
  return undefined;
}
