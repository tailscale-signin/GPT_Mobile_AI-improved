import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { lstatSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), 'upstream-dependencies');
const manifest = JSON.parse(readFileSync(resolve(root, 'manifest.json'), 'utf8'));
const files = ['package.json', 'package-lock.json'];
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const textBytes = (bytes, knownText = true) => {
  if (!knownText) return bytes;
  const text = bytes.toString('utf8');
  return !bytes.includes(0) && Buffer.from(text, 'utf8').equals(bytes) ? Buffer.from(text.replace(/\r\n/g, '\n'), 'utf8') : bytes;
};
const readRegularFile = path => {
  if (!lstatSync(path).isFile()) throw new Error('Upstream source must be a regular file; symbolic links are not replaced.');
  return readFileSync(path);
};

function recordFor(name, revision) {
  if (!Object.hasOwn(manifest.upstreams, name) || manifest.upstreams[name].revision !== revision) throw new Error('Unexpected upstream dependency identity.');
  return manifest.upstreams[name];
}

/** A matching HEAD cannot authorize overwriting user edits or building additional source. */
export function verifyUpstreamSourceState(name, folder, revision) {
  const record = recordFor(name, revision);
  if (execFileSync('git', ['rev-parse', 'HEAD'], { cwd: folder, encoding: 'utf8' }).trim() !== revision) throw new Error('Unexpected upstream checkout revision.');
  try {
    execFileSync('git', ['diff', '--cached', '--quiet', revision, '--'], { cwd: folder, stdio: 'pipe' });
  } catch {
    throw new Error('Upstream checkout has staged changes; preserve them before preparation.');
  }
  const entries = execFileSync('git', ['ls-tree', '-r', '-z', revision], { cwd: folder, encoding: 'utf8' }).split('\0').filter(Boolean);
  for (const entry of entries) {
    const split = entry.indexOf('\t');
    const [mode, type] = entry.slice(0, split).split(' ');
    const file = entry.slice(split + 1);
    if (!['100644', '100755'].includes(mode) || type !== 'blob') throw new Error('Unsupported upstream source entry.');
    if (files.includes(file)) continue;
    const bytes = readRegularFile(resolve(folder, file));
    const original = execFileSync('git', ['show', `${revision}:${file}`], { cwd: folder });
    const knownText = record.textFiles?.includes(file) === true;
    // Permit ordinary Git LF/CRLF checkout conversion, not arbitrary filters or edited content.
    if (textBytes(bytes, knownText).equals(textBytes(original, knownText))) continue;
    const accepted = record.allowedModifiedSources?.[file];
    if (!accepted?.includes(digest(bytes)) && !accepted?.includes(digest(textBytes(bytes, knownText)))) throw new Error('Upstream source has custom changes; preserve them before preparation.');
  }
  if (execFileSync('git', ['ls-files', '--others', '--exclude-standard', '-z'], { cwd: folder }).length) {
    throw new Error('Upstream checkout has unreviewed files; preserve them before preparation.');
  }
}

/** Validate the whole pair before replacing either file. A partial prior install can be resumed. */
export function validateDependencyOverlay(record, revision, original, current, overlay) {
  if (!record || record.revision !== revision || !/^[a-f0-9]{40}$/.test(revision)) {
    throw new Error('Unexpected upstream revision for dependency overlay.');
  }
  for (const file of files) {
    if (digest(original[file]) !== record.upstreamSha256[file]) throw new Error(`Pinned upstream ${file} changed.`);
    if (digest(overlay[file]) !== record.overlaySha256[file]) throw new Error(`Reviewed dependency ${file} changed.`);
    const accepted = [record.upstreamSha256[file], record.overlaySha256[file]];
    if (!accepted.includes(digest(current[file])) && !accepted.includes(digest(textBytes(current[file])))) {
      throw new Error(`Local upstream ${file} has custom changes; preserve them before preparing dependencies.`);
    }
  }
}

/** Upstream code stays at its immutable commit; npm ci consumes this reviewed, exact lockfile. */
export function installUpstreamDependencies(name, folder, revision) {
  const record = recordFor(name, revision);
  verifyUpstreamSourceState(name, folder, revision);
  const original = Object.fromEntries(files.map(file => [file, execFileSync('git', ['show', `${revision}:${file}`], { cwd: folder })]));
  const current = Object.fromEntries(files.map(file => [file, readRegularFile(resolve(folder, file))]));
  const overlay = Object.fromEntries(files.map(file => [file, readFileSync(resolve(root, name, file))]));
  validateDependencyOverlay(record, revision, original, current, overlay);
  for (const file of files) {
    if (!current[file].equals(overlay[file])) writeFileSync(resolve(folder, file), overlay[file]);
  }
}
