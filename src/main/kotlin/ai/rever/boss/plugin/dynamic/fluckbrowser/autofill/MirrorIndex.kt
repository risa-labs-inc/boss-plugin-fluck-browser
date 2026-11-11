package ai.rever.boss.plugin.dynamic.fluckbrowser.autofill

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Keys of the Chromium records the mirror owns, per profile path, plus every key this install has
 * ever written there ([written]), so adoption never claims a record the user saved themselves.
 * Keys only, no values, but `pw|origin|username` keys still name sites and usernames, so the file
 * is owner-only (0600) where the filesystem allows.
 */
internal class MirrorIndex(
    private val file: File,
) {
    private val serializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
    private val byProfile: MutableMap<String, MutableSet<String>> by lazy { load() }

    private fun load(): MutableMap<String, MutableSet<String>> =
        runCatching {
            if (!file.exists()) return@runCatching mutableMapOf()
            Json.decodeFromString(serializer, file.readText()).mapValuesTo(mutableMapOf()) { it.value.toMutableSet() }
        }.getOrElse { mutableMapOf() }

    @Synchronized
    fun keys(profile: String): Set<String> = byProfile[profile]?.toSet().orEmpty()

    /** The user whose rows were last mirrored into [profile] (an id, no PII). */
    @Synchronized
    fun owner(profile: String): String? = byProfile[profile + OWNER]?.firstOrNull()

    @Synchronized
    fun setOwner(
        profile: String,
        userId: String,
    ) {
        if (profile.isNotEmpty()) byProfile[profile + OWNER] = mutableSetOf(userId)
    }

    /** Every key this install has written into [profile], owned now or not. */
    @Synchronized
    fun written(profile: String): Set<String> = byProfile[profile + WRITTEN]?.toSet().orEmpty()

    @Synchronized
    fun add(
        profile: String,
        key: String,
    ) {
        if (profile.isEmpty()) return
        byProfile.getOrPut(profile) { mutableSetOf() } += key
        byProfile.getOrPut(profile + WRITTEN) { mutableSetOf() } += key
    }

    @Synchronized
    fun remove(
        profile: String,
        key: String,
    ) {
        byProfile[profile]?.remove(key)
    }

    @Synchronized
    fun clear(profile: String) {
        byProfile.remove(profile)
    }

    @Synchronized
    fun save() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        val snapshot = byProfile.filterValues { it.isNotEmpty() }.mapValues { it.value.sorted() }
        tmp.delete()
        runCatching {
            Files.createFile(
                tmp.toPath(),
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                ),
            )
        }
        tmp.writeText(Json.encodeToString(serializer, snapshot))
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val WRITTEN = "#written"
        const val OWNER = "#user"
    }
}
