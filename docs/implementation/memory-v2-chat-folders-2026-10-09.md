# Memory v2 foundation and chat organization

Implements the first development slice of `Memory_Plugin_V2_Design_Implementation.docx` plus compact bottom navigation, a latest-response scroll boundary, and persistent chat folders.

## Delivered behavior

- New conflicting automatic facts remain disabled proposals. Existing assertions are unchanged until explicit review. The review screen offers Replace existing, Keep both, and Keep existing. Replacement fingerprints are persisted and checked under the vault mutex; changed or deleted originals reject stale approval without a partial write.
- Repeated residence episodes preserve the original assertion ID and assign a new ID when returning to a retired value. Occupation is no longer assumed to be exclusive.
- Missing or invalidated Keystore keys preserve encrypted records and report failure. Fact-vector maintenance clears only fact vectors, preserving document retrieval.
- Fresh vaults default cloud recall off; previously persisted settings retain their values. The existing cloud switch remains the opt-in path.
- Room schema 35 introduces encrypted-payload memory fact, link, pending-change, and authority-marker contracts. Valid-time queries accept future expiry and use half-open intervals. A separate memory cipher uses AES-GCM, fresh nonces, versioned envelopes, and authenticated record identity/revision/scope. Destructive downgrade fallback is removed.
- The bottom arrow is visually 26 dp, inside a 48 dp touch target. Generation pulses smoothly between 82% and 100% opacity with a 1.5-second cycle.
- Only a released upward fling at least 1,000 dp/s pauses at the newest completed response header for up to one second. Manual drags and slow flings pass freely. Touch cancels the pause immediately; favourite-targeted entries initially bypass the guard. The half-viewport entry spacer is removed, and an unavailable target measurement can no longer hide the chat indefinitely.
- Conversation long-press drag reveals pin-left and new-folder-right targets. Existing folder chips accept drops and filter the chat list. Holding a folder opens rename, colour and removal controls. Removing a folder keeps its conversations. Folder data is stored in Room and included in conversation backups, with legacy JSON conversion support.

## Memory rollout boundary

The existing encrypted JSON vault is still authoritative. The new Room memory tables are foundation contracts, not an enabled replacement store. No existing memory is imported or deleted by this schema migration. Document RAG continues using the current 100-dimensional MediaPipe/ObjectBox path.

Remaining design stages: a complete MemoryStore and transactional mutation service; evidence, suppression, audit, staging and protected standalone graph tables; crash-safe external-vault import with digest/read-back verification; authority cutover; independent lexical/vector rank fusion; destination rechecks; history/effective-date editing; opt-in proactive suggestions; portable encrypted native interchange. These require their own migration, privacy and device gates. Existing graph projections have not yet been migrated to encrypted graph records.

## Validation boundaries

Unit regression coverage is added for conflict persistence, stale approval, keep-both, recurrence history, valid time, half-life decay, authenticated envelopes and scroll release timing. Folder relational and schema migration checks cover persistence/cascades. Actual touch gestures, font scaling, Android Keystore failure and native fact/document vector coexistence require device instrumentation; no device measurement is inferred from JVM results.
