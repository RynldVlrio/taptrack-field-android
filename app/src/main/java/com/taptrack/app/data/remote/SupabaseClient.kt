package com.taptrack.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Server refused the request (4xx/5xx). Not retried automatically — shown to the user. */
class ApiException(val code: Int, message: String) : Exception(message)

/** Session expired and could not be refreshed — user must sign in again. */
class AuthExpiredException : Exception("Session expired — please sign in again")

/**
 * Minimal Supabase client over plain HTTP (Auth + PostgREST + Storage).
 * Every call needs network; offline callers catch [IOException].
 */
class SupabaseClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val session: SessionStore,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val refreshLock = Mutex()

    // ── Auth ────────────────────────────────────────────────────────────────

    data class SignInResult(val userId: String, val email: String?)

    suspend fun signIn(email: String, password: String): SignInResult = withContext(Dispatchers.IO) {
        val body = JSONObject().put("email", email).put("password", password).toString().toRequestBody(jsonType)
        val req = Request.Builder()
            .url("$baseUrl/auth/v1/token?grant_type=password")
            .header("apikey", apiKey)
            .post(body).build()
        val json = JSONObject(execute(req, authFlow = true))
        saveTokenResponse(json)
        val user = json.getJSONObject("user")
        SignInResult(user.getString("id"), user.optStringOrNull("email"))
    }

    /** Email a one-time code. createUser=true for "open an account", false for "forgot password". */
    suspend fun sendEmailCode(email: String, createUser: Boolean, fullName: String? = null) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("email", email).put("create_user", createUser)
        if (!fullName.isNullOrBlank()) body.put("data", JSONObject().put("full_name", fullName))
        val req = Request.Builder().url("$baseUrl/auth/v1/otp").header("apikey", apiKey)
            .post(body.toString().toRequestBody(jsonType)).build()
        execute(req, authFlow = true)
        Unit
    }

    /** Check the emailed code → signed in (tokens saved). */
    suspend fun verifyEmailCode(email: String, code: String): SignInResult = withContext(Dispatchers.IO) {
        val body = JSONObject().put("type", "email").put("email", email).put("token", code)
        val req = Request.Builder().url("$baseUrl/auth/v1/verify").header("apikey", apiKey)
            .post(body.toString().toRequestBody(jsonType)).build()
        val json = JSONObject(execute(req, authFlow = true))
        saveTokenResponse(json)
        val user = json.getJSONObject("user")
        SignInResult(user.getString("id"), user.optStringOrNull("email"))
    }

    /** Set the signed-in user's password. */
    suspend fun updatePassword(password: String) {
        ensureFreshToken()
        withContext(Dispatchers.IO) {
            val req = authed("$baseUrl/auth/v1/user").put(JSONObject().put("password", password).toString().toRequestBody(jsonType)).build()
            execute(req, authFlow = true)
        }
    }

    /** Call a database function (signed in). Returns the raw JSON text. */
    suspend fun rpc(name: String, args: JSONObject = JSONObject()): String {
        ensureFreshToken()
        return withContext(Dispatchers.IO) {
            execute(authed("$baseUrl/rest/v1/rpc/$name").post(args.toString().toRequestBody(jsonType)).build())
        }
    }

    /** Call a database function before sign-in (e.g. join_info). */
    suspend fun rpcAnon(name: String, args: JSONObject): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$baseUrl/rest/v1/rpc/$name").header("apikey", apiKey)
            .post(args.toString().toRequestBody(jsonType)).build()
        execute(req)
    }

    /** Refreshes the access token when it is about to expire. */
    suspend fun ensureFreshToken() {
        if (session.accessToken != null && session.expiresAt - 60 > System.currentTimeMillis() / 1000) return
        refreshLock.withLock {
            if (session.accessToken != null && session.expiresAt - 60 > System.currentTimeMillis() / 1000) return
            val refresh = session.refreshToken ?: throw AuthExpiredException()
            withContext(Dispatchers.IO) {
                val body = JSONObject().put("refresh_token", refresh).toString().toRequestBody(jsonType)
                val req = Request.Builder()
                    .url("$baseUrl/auth/v1/token?grant_type=refresh_token")
                    .header("apikey", apiKey)
                    .post(body).build()
                try {
                    saveTokenResponse(JSONObject(execute(req, authFlow = true)))
                } catch (e: ApiException) {
                    if (e.code in 400..499) throw AuthExpiredException() else throw e
                }
            }
        }
    }

    private fun saveTokenResponse(json: JSONObject) {
        session.saveTokens(json.getString("access_token"), json.getString("refresh_token"), json.optLong("expires_in", 3600))
    }

    // ── PostgREST ─────────────────────────────────────────────────────────────

    /** GET /rest/v1/{pathAndQuery} → JSON array */
    suspend fun select(pathAndQuery: String): JSONArray {
        ensureFreshToken()
        return withContext(Dispatchers.IO) {
            JSONArray(execute(authed("$baseUrl/rest/v1/$pathAndQuery").get().build()))
        }
    }

    /** POST (insert) one row, returns the stored row (with server-assigned values). */
    suspend fun insert(table: String, row: JSONObject): JSONObject {
        ensureFreshToken()
        return withContext(Dispatchers.IO) {
            val req = authed("$baseUrl/rest/v1/$table")
                .header("Prefer", "return=representation")
                .post(row.toString().toRequestBody(jsonType)).build()
            firstRow(execute(req), table)
        }
    }

    /** PATCH one row by id, returns the stored row. */
    suspend fun update(table: String, id: String, changes: JSONObject): JSONObject {
        ensureFreshToken()
        return withContext(Dispatchers.IO) {
            val req = authed("$baseUrl/rest/v1/$table?id=eq.$id")
                .header("Prefer", "return=representation")
                .patch(changes.toString().toRequestBody(jsonType)).build()
            firstRow(execute(req), table)
        }
    }

    /** Anonymous read (e.g. app_config) — works before sign-in. */
    suspend fun selectAnon(pathAndQuery: String): JSONArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$baseUrl/rest/v1/$pathAndQuery")
            .header("apikey", apiKey).get().build()
        JSONArray(execute(req))
    }

    // ── Storage ───────────────────────────────────────────────────────────────

    suspend fun uploadFile(bucket: String, path: String, file: File, contentType: String = "image/jpeg") {
        ensureFreshToken()
        withContext(Dispatchers.IO) {
            val body: RequestBody = file.asRequestBody(contentType.toMediaType())
            val req = authed("$baseUrl/storage/v1/object/$bucket/$path")
                .header("x-upsert", "true")
                .post(body).build()
            execute(req)
        }
    }

    /** Download a Storage object (private bucket) as bytes. */
    suspend fun downloadFile(bucket: String, path: String): ByteArray {
        ensureFreshToken()
        return withContext(Dispatchers.IO) {
            val req = authed("$baseUrl/storage/v1/object/$bucket/$path").get().build()
            http.newCall(req).execute().use { res ->
                if (res.code == 401) throw AuthExpiredException()
                if (!res.isSuccessful) throw ApiException(res.code, "Download failed (${res.code})")
                res.body?.bytes() ?: ByteArray(0)
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun authed(url: String): Request.Builder =
        Request.Builder().url(url)
            .header("apikey", apiKey)
            .header("Authorization", "Bearer ${session.accessToken ?: throw AuthExpiredException()}")

    private fun firstRow(body: String, table: String): JSONObject {
        val arr = JSONArray(body)
        if (arr.length() == 0) throw ApiException(403, "Not allowed to change this $table record")
        return arr.getJSONObject(0)
    }

    /** Runs the request; returns body text or throws [ApiException] / [IOException]. */
    private fun execute(req: Request, authFlow: Boolean = false): String {
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (res.isSuccessful) return text
            if (res.code == 401 && !authFlow) throw AuthExpiredException()
            throw ApiException(res.code, readableError(text, res.code))
        }
    }

    private fun readableError(body: String, code: Int): String {
        val msg = try {
            val o = JSONObject(body)
            o.optStringOrNull("message") ?: o.optStringOrNull("error_description") ?: o.optStringOrNull("msg") ?: o.optStringOrNull("error")
        } catch (_: Exception) { null }
        return when {
            msg == null -> "Server error ($code)"
            msg.contains("Invalid login credentials") -> "Wrong email or password"
            msg.contains("row-level security") -> "You don't have permission to do that"
            Regex("rate limit|security purposes", RegexOption.IGNORE_CASE).containsMatchIn(msg) -> "Too many tries — wait a minute and try again"
            Regex("expired|invalid.*(otp|token)", RegexOption.IGNORE_CASE).containsMatchIn(msg) -> "That code is wrong or has expired. Request a new one."
            Regex("signups? not allowed|signup.*disabled", RegexOption.IGNORE_CASE).containsMatchIn(msg) -> "New accounts are switched off on the server"
            Regex("different from the old|same as", RegexOption.IGNORE_CASE).containsMatchIn(msg) -> "Choose a password different from the old one"
            else -> msg
        }
    }
}

/** JSONObject.optString returns "null" for JSON null — this returns a real null. */
fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key)

fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (!has(key) || isNull(key)) null else getDouble(key)

fun JSONObject.optIntOrNull(key: String): Int? =
    if (!has(key) || isNull(key)) null else getInt(key)

/** Put a value or JSON null. */
fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)
