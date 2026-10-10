package app.rev.data.remote

import app.rev.domain.model.Result
import app.rev.domain.model.ServerInfo
import app.rev.domain.model.ServerUrl
import app.rev.domain.repository.LoginError
import app.rev.domain.repository.ProbeError
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

/** Typed calls to the REV server API. Knows URLs and wire formats; no app state. */
class RevApi(private val http: RevHttpClient) {

    /** [fast]: short timeouts for probing a home-network address, which answers instantly or never. */
    suspend fun health(server: ServerUrl, fast: Boolean = false): Result<ServerInfo, ProbeError> {
        val response = request {
            http.anonymousClient.get("${server.value}/api/system/health") {
                timeout {
                    connectTimeoutMillis = if (fast) 2_000 else 5_000
                    requestTimeoutMillis = if (fast) 3_000 else 8_000
                }
            }
        } ?: return Result.Err(ProbeError.Unreachable)

        if (!response.status.isSuccess()) return Result.Err(ProbeError.NotRev)
        val health = try {
            response.body<HealthDto>()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Result.Err(ProbeError.NotRev)
        }
        if (health.name != "REV" || health.status != "ok") return Result.Err(ProbeError.NotRev)
        return Result.Ok(ServerInfo(version = health.version, instanceId = health.instanceId))
    }

    internal suspend fun login(
        server: ServerUrl,
        username: String,
        password: String,
        deviceName: String?,
    ): Result<TokenResponseDto, LoginError> {
        val response = request {
            http.anonymousClient.post("${server.value}/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequestDto(username, password, deviceName))
            }
        } ?: return Result.Err(LoginError.Unreachable)

        return when {
            response.status.isSuccess() -> Result.Ok(response.body<TokenResponseDto>())
            response.status == HttpStatusCode.Unauthorized -> Result.Err(LoginError.InvalidCredentials)
            response.status == HttpStatusCode.TooManyRequests -> Result.Err(LoginError.RateLimited)
            else -> Result.Err(LoginError.Unexpected(response.errorMessage()))
        }
    }

    /** Revokes the current session on the configured server. Returns false if that wasn't possible. */
    suspend fun logout(server: ServerUrl): Boolean {
        val response = request { http.client.post("${server.value}/api/auth/logout") } ?: return false
        return response.status.isSuccess()
    }

    /** Runs a request, mapping transport failures (DNS, refused, timeout, TLS) to null. */
    private suspend fun request(block: suspend () -> HttpResponse): HttpResponse? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun HttpResponse.errorMessage(): String = try {
        body<ApiErrorDto>().error.message
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        "Server error (${status.value})"
    }
}
