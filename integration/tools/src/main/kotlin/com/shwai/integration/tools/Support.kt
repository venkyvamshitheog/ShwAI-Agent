package com.shwai.integration.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

internal fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

internal fun str(description: String, enum: List<String>? = null): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    if (enum != null) put("enum", JsonArray(enum.map { JsonPrimitive(it) }))
}

internal fun integer(description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

internal fun strArray(description: String): JsonObject = buildJsonObject {
    put("type", "array")
    put("description", description)
    put("items", buildJsonObject { put("type", "string") })
}

internal fun objectSchema(required: List<String>, vararg props: Pair<String, JsonObject>): JsonObject =
    buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { props.forEach { (k, v) -> put(k, v) } })
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }

internal fun Context.hasMediaAccess(): Boolean {
    val perms = if (Build.VERSION.SDK_INT >= 33) {
        buildList {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.READ_MEDIA_AUDIO)
            if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }
    } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    return perms.any { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
}

/** Only MediaStore URIs are accepted from the model; blocks arbitrary content:// provider access. */
internal fun isMediaStoreUri(uri: Uri): Boolean =
    uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY

internal fun Context.describe(uri: Uri): Pair<String, Long> {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { c ->
            if (c.moveToFirst()) return (c.getString(0) ?: "file") to c.getLong(1)
        }
    return "file" to -1L
}
