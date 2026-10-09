import { randomUUID } from 'node:crypto';

export class TranscriptError extends Error {
  constructor(code, message) { super(message); this.code = code; }
}
const fail = (code, message) => { throw new TranscriptError(code, message); };
const ID = /^[A-Za-z0-9_-]{11}$/;

export function videoId(value) {
  if (typeof value !== 'string' || value.length > 2048) fail('INVALID_VIDEO', 'Supply a video ID or supported YouTube URL.');
  if (ID.test(value)) return value;
  let url;
  try { url = new URL(value); } catch { fail('INVALID_VIDEO', 'Invalid YouTube URL.'); }
  if (url.protocol !== 'https:' || url.username || url.password || url.port) fail('INVALID_VIDEO', 'Use a public HTTPS YouTube URL.');
  let id;
  if (url.hostname === 'youtu.be') id = url.pathname.slice(1);
  else if (['youtube.com', 'www.youtube.com', 'm.youtube.com'].includes(url.hostname)) {
    if (url.pathname === '/watch' && url.searchParams.getAll('v').length === 1) id = url.searchParams.get('v');
    else if (/^\/(shorts|embed)\/[^/]+$/.test(url.pathname)) id = url.pathname.split('/')[2];
  }
  if (!ID.test(id ?? '')) fail('INVALID_VIDEO', 'Unsupported YouTube video reference.');
  return id;
}

export function normalizeTrack(input) {
  if (!input || typeof input.language !== 'string' || !/^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$/.test(input.language)) fail('MALFORMED_RESPONSE', 'Track language is missing or invalid.');
  if (!['manual', 'generated', 'translated'].includes(input.kind)) fail('MALFORMED_RESPONSE', 'Track provenance is required.');
  if (input.kind === 'translated' && typeof input.sourceLanguage !== 'string') fail('MALFORMED_RESPONSE', 'Translation source language is required.');
  if (!Array.isArray(input.segments) || !input.segments.length || input.segments.length > 100000) fail('EMPTY_OR_OVERSIZED', 'Caption segments are empty or too numerous.');
  let bytes = 0;
  let previousStart = -1;
  const segments = input.segments.map((s, index) => {
    if (!s || typeof s.text !== 'string' || !Number.isFinite(s.start) || !Number.isFinite(s.duration) || s.start < previousStart || s.start < 0 || s.duration < 0 || !Number.isFinite(s.start + s.duration)) fail('MALFORMED_RESPONSE', 'Invalid or unordered caption timestamps.');
    previousStart = s.start;
    bytes += Buffer.byteLength(s.text, 'utf8');
    if (bytes > 5 * 1024 * 1024) fail('EMPTY_OR_OVERSIZED', 'Transcript exceeds the artifact byte limit.');
    return Object.freeze({ id: index, start: s.start, duration: s.duration, text: s.text });
  });
  return Object.freeze({ language: input.language, kind: input.kind, sourceLanguage: input.sourceLanguage ?? null, complete: input.complete === true, segments: Object.freeze(segments), bytes });
}

/** Trusted context comes from the host's permission router, never from model arguments.
 * This first work package is not exposed as an MCP server yet.
 */
export class TranscriptArtifacts {
  constructor({ fetchTrack, clock = Date.now, ttlMs = 3600000, maxEntries = 16, maxFlights = 2 } = {}) {
    if (typeof fetchTrack !== 'function' || !Number.isInteger(ttlMs) || ttlMs <= 0 || !Number.isInteger(maxEntries) || maxEntries < 1 || maxEntries > 64 || !Number.isInteger(maxFlights) || maxFlights < 1 || maxFlights > 16) throw new TypeError('Invalid artifact store configuration.');
    this.fetchTrack = fetchTrack; this.clock = clock; this.ttlMs = ttlMs; this.maxEntries = maxEntries; this.maxFlights = maxFlights;
    this.artifacts = new Map(); this.keys = new Map(); this.flights = new Map();
  }
  authorize(context) {
    if (!context?.allowed || typeof context.scope !== 'string' || !context.scope || context.scope.length > 256) fail('DENIED', 'Transcript access is not authorized.');
  }
  prune() {
    for (const [id, artifact] of this.artifacts) if (artifact.expiresAt <= this.clock()) this.artifacts.delete(id);
    for (const [key, id] of this.keys) if (!this.artifacts.has(id)) this.keys.delete(key);
  }
  async acquire(request, context) {
    this.authorize(context); this.prune();
    const scope = context.scope;
    const id = videoId(request.video);
    const languages = request.languages ?? ['en'];
    if (!Array.isArray(languages) || !languages.length || languages.length > 8 || languages.some(x => typeof x !== 'string' || !/^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$/.test(x))) fail('INVALID_REQUEST', 'Supply up to eight preferred languages.');
    if (typeof request.configurationRevision !== 'string' || !request.configurationRevision || request.configurationRevision.length > 256) fail('INVALID_REQUEST', 'A configuration revision is required.');
    if (request.track != null && (typeof request.track !== 'string' || request.track.length > 256)) fail('INVALID_REQUEST', 'Invalid track handle.');
    const allowTranslation = request.allowTranslation === true;
    const normalized = Object.freeze({ videoId: id, languages: Object.freeze([...languages]), track: request.track ?? null, allowTranslation, configurationRevision: request.configurationRevision });
    const key = JSON.stringify([scope, normalized]);
    const cached = this.artifacts.get(this.keys.get(key));
    if (cached) return { artifact: cached, cached: true };
    if (this.flights.has(key)) {
      const result = await this.flights.get(key);
      this.authorize(context);
      if (context.scope !== scope) fail('DENIED', 'Transcript scope changed.');
      return result;
    }
    if (this.flights.size >= this.maxFlights) fail('BUSY', 'Caption acquisition is busy. Try later.');
    // Defer acquisition so the promise is registered before synchronous adapters run.
    const flight = Promise.resolve().then(async () => {
      this.authorize(context);
      const track = normalizeTrack(await this.fetchTrack(normalized));
      this.authorize(context);
      if (context.scope !== scope) fail('DENIED', 'Transcript scope changed.');
      if (track.kind === 'translated' && !allowTranslation) fail('TRANSLATION_DENIED', 'Translation was not allowed.');
      const acquiredAt = this.clock();
      const artifact = Object.freeze({ id: randomUUID(), scope, videoId: id, configurationRevision: normalized.configurationRevision, acquiredAt, expiresAt: acquiredAt + this.ttlMs, ...track });
      this.prune();
      while (this.artifacts.size >= this.maxEntries) this.artifacts.delete(this.artifacts.keys().next().value);
      this.prune(); this.artifacts.set(artifact.id, artifact); this.keys.set(key, artifact.id);
      return { artifact, cached: false };
    }).finally(() => this.flights.delete(key));
    this.flights.set(key, flight);
    return flight;
  }
  read(id, { cursor = null, maxCharacters = 8000, forRemoteModel = false } = {}, context) {
    this.authorize(context); this.prune();
    if (forRemoteModel && context.allowRemoteExcerpts !== true) fail('DENIED', 'Remote transcript excerpts are not authorized.');
    const artifact = this.artifacts.get(id);
    if (!artifact || artifact.scope !== context.scope) fail('ARTIFACT_UNAVAILABLE', 'Transcript artifact is unavailable.');
    if (!Number.isInteger(maxCharacters) || maxCharacters < 100 || maxCharacters > 16000) fail('INVALID_REQUEST', 'Page budget must be between 100 and 16000 characters.');
    let index = 0;
    if (cursor != null) {
      if (typeof cursor !== 'string' || cursor.length > 512) fail('INVALID_CURSOR', 'Invalid transcript cursor.');
      let decoded;
      try { decoded = JSON.parse(Buffer.from(cursor, 'base64url').toString('utf8')); } catch { fail('INVALID_CURSOR', 'Invalid transcript cursor.'); }
      if (decoded?.version !== 1 || decoded.id !== id || !Number.isInteger(decoded.index) || decoded.index < 0 || decoded.index > artifact.segments.length) fail('INVALID_CURSOR', 'Cursor does not identify this artifact.');
      index = decoded.index;
    }
    const startIndex = index; const segments = []; let size = 0;
    while (index < artifact.segments.length) {
      const segment = artifact.segments[index];
      const cost = JSON.stringify(segment).length + 1;
      if (size + cost > maxCharacters) {
        if (!segments.length) fail('SEGMENT_TOO_LARGE', 'This caption segment exceeds the page budget. Increase the budget or import split segments.');
        break;
      }
      size += cost; segments.push(segment); index++;
    }
    return { artifactId: id, language: artifact.language, kind: artifact.kind, completeTrack: artifact.complete, startIndex, segments, nextCursor: index < artifact.segments.length ? Buffer.from(JSON.stringify({ version: 1, id, index })).toString('base64url') : null };
  }
}
