package com.brickssoft.tracking.httpqueue

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

internal object QueueJson {
    fun obj(text: String): JSONObject {
        val parser = JSONTokener(text)
        val value = parser.nextValue()
        require(value is JSONObject && parser.nextClean() == '\u0000') { "Expected one JSON object" }
        return value
    }
    fun map(text: String): Map<String, String> = obj(text).let { o ->
        o.keys().asSequence().associateWith { o.getString(it) }
    }
    fun encodeMap(map: Map<String, String>): String = JSONObject(map).toString()
    fun request(value: RenderedRequest): String = JSONObject().apply {
        put("url", value.url); put("method", value.method); put("headers", JSONObject(value.headers))
        put("body", value.body ?: JSONObject.NULL); put("contentType", value.contentType)
    }.toString()
    fun request(text: String): RenderedRequest = obj(text).let {
        RenderedRequest(it.getString("url"), it.getString("method"), map(it.getJSONObject("headers").toString()),
            if (it.isNull("body")) null else it.getString("body"), it.getString("contentType"))
    }
}

internal val standardCredentials = setOf("authorization", "proxy-authorization", "cookie")
internal fun String.headerKey(): String = lowercase(Locale.ROOT)
internal fun QueueConfig.reservedHeaders(): Set<String> = standardCredentials + credentialHeaderNames.map { it.headerKey() }
internal fun validateHeaders(headers: Map<String, String>, reserved: Set<String> = emptySet()) {
    require(headers.keys.map { it.headerKey() }.distinct().size == headers.size) { "Duplicate header name" }
    for ((key, value) in headers) {
        require(key.headerKey() !in reserved) { "Credentials must use AuthorizationStore" }
        Headers.Builder().add(key, value)
    }
}
internal fun validateDestination(url: String, allowCleartext: Boolean) {
    val parsed = url.toHttpUrl()
    require(parsed.isHttps || allowCleartext) { "HTTPS required" }
    require(parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.fragment == null) { "Invalid destination" }
}
internal fun sameOrigin(a: String, b: String): Boolean {
    val x = a.toHttpUrl(); val y = b.toHttpUrl()
    return x.scheme == y.scheme && x.host == y.host && x.port == y.port
}
internal fun QueueConfig.validate() {
    require(queueId.isNotBlank() && scopeKey.isNotBlank() && revision > 0 && schemaVersion == 1)
    require(maxBatchSize in 1..10_000 && autoSyncThreshold >= 0 && maxBatchAgeSeconds in 0..86_400)
    require(timeoutMillis in 1..900_000)
    validateDestination(endpoint, allowCleartext)
    validateHeaders(headers, reservedHeaders())
    val params = QueueJson.obj(paramsJson)
    if (encoderKind == EncoderKind.JSON_RECORD_BATCH) {
        require(method in setOf("POST", "PUT", "PATCH") && rootProperty.isNotBlank() && !params.has(rootProperty))
    }
    validateRequest(RenderedRequest(endpoint, method, headers, if (method in setOf("GET", "HEAD")) null else "{}"))
}
internal fun validateRequest(value: RenderedRequest) {
    val body = value.body?.toRequestBody(value.contentType.toMediaType())
    Request.Builder().url(value.url).method(value.method, body).build()
    validateHeaders(value.headers)
}

/** Exactly two persisted encodings; workers never need a host callback to reconstruct a request. */
internal object PayloadEncoder {
    fun encode(config: QueueConfig, items: List<StoredItem>, authorization: Authorization?): RenderedRequest {
        val rendered = when (config.encoderKind) {
            EncoderKind.SINGLE_REQUEST -> requireNotNull(items.single().record.request)
            EncoderKind.JSON_RECORD_BATCH -> RenderedRequest(config.endpoint, config.method, body =
                QueueJson.obj(config.paramsJson).apply {
                    put(config.rootProperty, JSONArray().apply {
                        for (item in items) put(QueueJson.obj(item.record.payloadJson))
                    })
                }.toString())
        }
        val headers = linkedMapOf<String, Pair<String, String>>()
        val entries = config.headers.entries + rendered.headers.entries + (authorization?.headers ?: emptyMap()).entries
        for (entry in entries) headers[entry.key.headerKey()] = entry.key to entry.value
        return rendered.copy(headers = headers.values.associate { it })
    }
}
