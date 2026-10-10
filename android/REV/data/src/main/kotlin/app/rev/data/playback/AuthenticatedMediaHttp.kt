package app.rev.data.playback

import app.rev.data.remote.RevHttpClient
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * OkHttp client for the media player (ExoPlayer streams), offline downloads and live events.
 * Mirrors [RevHttpClient]'s rules: the session token is attached only to requests for the
 * configured server's addresses, a 401 triggers one refresh and retry, and redirects are not
 * followed. These callbacks run on OkHttp/loader threads, so blocking is acceptable.
 */
fun authenticatedMediaHttpClient(http: RevHttpClient): OkHttpClient {
    val attachToken = Interceptor { chain ->
        val request = chain.request()
        val token = runBlocking { http.accessTokenFor(request.url.toString()) }
        try {
            chain.proceed(if (token != null) request.newBuilder().header(AUTHORIZATION, "Bearer $token").build() else request)
        } catch (e: IOException) {
            if (token != null) http.reportUnreachable(request.url.toString())
            throw e
        }
    }
    val refreshOn401 = Authenticator { _, response ->
        val sent = response.request.header(AUTHORIZATION)?.removePrefix("Bearer ") ?: return@Authenticator null
        if (response.priorResponseCount() >= 1) return@Authenticator null // already retried once
        val fresh = runBlocking { http.refreshedAccessToken(sent) } ?: return@Authenticator null
        response.request.newBuilder().header(AUTHORIZATION, "Bearer $fresh").build()
    }
    return OkHttpClient.Builder()
        .addInterceptor(attachToken)
        .authenticator(refreshOn401)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}

private const val AUTHORIZATION = "Authorization"

private fun Response.priorResponseCount(): Int {
    var count = 0
    var prior = priorResponse
    while (prior != null) {
        count++
        prior = prior.priorResponse
    }
    return count
}
