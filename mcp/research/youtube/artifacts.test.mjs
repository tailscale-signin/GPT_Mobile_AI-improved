import test from 'node:test';
import assert from 'node:assert/strict';
import { TranscriptArtifacts, TranscriptError, normalizeTrack, videoId } from './artifacts.mjs';
const context = { allowed: true, scope: 'profile-a' };
const request = { video: 'abcdefghijk', configurationRevision: 'endpoint-v1' };
const track = () => ({ language: 'en', kind: 'manual', complete: true, segments: Array.from({ length: 12 }, (_, i) => ({ start: i * 2, duration: 2, text: 'Caption text '.repeat(4) })) });
const code = expected => e => e instanceof TranscriptError && e.code === expected;

test('validates video references and rejects foreign hosts and credential URLs', () => {
  for (const url of ['abcdefghijk', 'https://youtu.be/abcdefghijk?t=4', 'https://www.youtube.com/watch?v=abcdefghijk', 'https://youtube.com/shorts/abcdefghijk']) assert.equal(videoId(url), 'abcdefghijk');
  for (const url of ['https://youtube.com.evil.org/watch?v=abcdefghijk', 'https://user:secret@youtube.com/watch?v=abcdefghijk', 'http://youtube.com/watch?v=abcdefghijk', 'https://youtube.com/watch?v=abcdefghijk&v=ABCDEFGHIJK']) assert.throws(() => videoId(url), code('INVALID_VIDEO'));
});
test('ten identical requests share one acquisition and cache respects scope and revision', async () => {
  let calls = 0;
  const store = new TranscriptArtifacts({ fetchTrack: async () => { calls++; return track(); } });
  const results = await Promise.all(Array.from({ length: 10 }, () => store.acquire(request, context)));
  assert.equal(calls, 1); assert.equal(new Set(results.map(r => r.artifact.id)).size, 1);
  assert.equal((await store.acquire(request, context)).cached, true);
  await store.acquire({ ...request, configurationRevision: 'endpoint-v2' }, context);
  await store.acquire(request, { ...context, scope: 'profile-b' }); assert.equal(calls, 3);
});
test('pagination advances and never refetches, preserves exact segment times', async () => {
  let calls = 0;
  const store = new TranscriptArtifacts({ fetchTrack: async () => { calls++; return track(); } });
  const { artifact } = await store.acquire(request, context);
  let cursor = null; let previous = -1; const segments = [];
  do {
    const page = store.read(artifact.id, { cursor, maxCharacters: 220 }, context);
    assert.ok(page.startIndex > previous); previous = page.startIndex;
    segments.push(...page.segments); cursor = page.nextCursor;
  } while (cursor);
  assert.equal(calls, 1); assert.deepEqual(segments, artifact.segments); assert.equal(segments[3].start, 6);
  assert.throws(() => store.read(artifact.id, { cursor: 'corrupt' }, context), code('INVALID_CURSOR'));
});
test('oversized segments produce an explicit error rather than an infinite empty page', async () => {
  const store = new TranscriptArtifacts({ fetchTrack: async () => ({ ...track(), segments: [{ start: 0, duration: 1, text: 'x'.repeat(1000) }] }) });
  const { artifact } = await store.acquire(request, context);
  assert.throws(() => store.read(artifact.id, { maxCharacters: 100 }, context), code('SEGMENT_TOO_LARGE'));
});
test('revoked grants, cross-profile reads, remote excerpts and expired artifacts are denied', async () => {
  let now = 10; let calls = 0;
  const store = new TranscriptArtifacts({ clock: () => now, ttlMs: 100, fetchTrack: async () => { calls++; return track(); } });
  const { artifact } = await store.acquire(request, context);
  await assert.rejects(store.acquire(request, { ...context, allowed: false }), code('DENIED')); assert.equal(calls, 1);
  assert.throws(() => store.read(artifact.id, {}, { ...context, scope: 'profile-b' }), code('ARTIFACT_UNAVAILABLE'));
  assert.throws(() => store.read(artifact.id, { forRemoteModel: true }, context), code('DENIED'));
  now = 111; assert.throws(() => store.read(artifact.id, {}, context), code('ARTIFACT_UNAVAILABLE')); assert.equal(calls, 1);
});
test('bad timestamps and unsolicited translations remain failures', async () => {
  assert.throws(() => normalizeTrack({ ...track(), segments: [{ start: -1, duration: 2, text: 'bad' }] }), code('MALFORMED_RESPONSE'));
  const store = new TranscriptArtifacts({ fetchTrack: async () => ({ ...track(), kind: 'translated', sourceLanguage: 'fr' }) });
  await assert.rejects(store.acquire(request, context), code('TRANSLATION_DENIED'));
});
test('failed acquisitions clear single-flight state and bounded admission rejects excess work', async () => {
  let release;
  const store = new TranscriptArtifacts({ maxFlights: 1, fetchTrack: () => new Promise(resolve => { release = resolve; }) });
  const pending = store.acquire(request, context); await Promise.resolve();
  await assert.rejects(store.acquire({ ...request, video: 'ABCDEFGHIJK' }, context), code('BUSY'));
  release(track()); await pending;
  let tries = 0;
  const failing = new TranscriptArtifacts({ fetchTrack: async () => { if (!tries++) throw new Error('offline'); return track(); } });
  await assert.rejects(failing.acquire(request, context), /offline/); assert.equal((await failing.acquire(request, context)).cached, false);
});

test('a shared acquisition rechecks each callers grant before returning', async () => {
  let release;
  const store = new TranscriptArtifacts({ fetchTrack: () => new Promise(resolve => { release = resolve; }) });
  const first = store.acquire(request, context);
  const revoked = { ...context };
  const second = store.acquire(request, revoked);
  await Promise.resolve(); revoked.allowed = false; release(track());
  await first;
  await assert.rejects(second, code('DENIED'));
});
