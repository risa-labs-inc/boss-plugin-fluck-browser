package ai.rever.boss.plugin.dynamic.fluckbrowser.autofill

/**
 * One reconcile step between what Secret Manager wants in Chromium and what is there.
 *
 * @property adopt Chromium records that match a current SM row but are missing from the index
 *   (a lost or stale index, a crash between add and index write). Taken into the index so normal
 *   removal covers them.
 * @property remove owned keys SM no longer has.
 * @property write items to (re)write; adopted ones are rewritten so Chromium holds SM's value.
 */
internal data class MirrorPlan(
    val adopt: Set<String>,
    val remove: Set<String>,
    val write: List<MirrorItem>,
)

internal object MirrorDiff {
    /**
     * @param desired SM rows mapped to items, by key.
     * @param present keys Chromium holds right now (ours and the user's own).
     * @param owned keys in the persisted index.
     * @param fingerprints in-memory value fingerprints of what the mirror last wrote, by key.
     */
    fun plan(
        desired: Map<String, MirrorItem>,
        present: Set<String>,
        owned: Set<String>,
        fingerprints: Map<String, String>,
    ): MirrorPlan {
        val adopt = (desired.keys intersect present) - owned
        val mine = owned + adopt
        val remove = owned - desired.keys
        val write =
            desired.values.filter { item ->
                !(item.key in mine && item.key in present && fingerprints[item.key] == item.fingerprint)
            }
        return MirrorPlan(adopt = adopt, remove = remove, write = write)
    }

    /**
     * What sign-out and quit remove: everything indexed, plus anything matching an SM row seen at
     * the last successful sync. A record matching neither may be the user's own and stays.
     */
    fun clearSet(
        owned: Set<String>,
        lastSyncedKeys: Set<String>,
        present: Set<String>,
    ): Set<String> = (owned + lastSyncedKeys) intersect present

    /**
     * Index keys to keep after a clear removed [targets]: all of them when any removal failed or the
     * store could not be listed afterwards ([after] null), else those still present. Dropping a key
     * whose record survived would leave it untracked for good, and autofillable for the next user.
     */
    fun keepAfterClear(
        targets: Set<String>,
        failed: Int,
        after: Set<String>?,
    ): Set<String> = if (failed > 0 || after == null) targets else after intersect targets
}
