package com.opentune.data.addons

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AddonVerificationRequestTest {
    private val request = Request.Builder().url("https://verification.invalid/auth/challenge").build()

    @Test fun cancellingAnEnrollmentWaitCancelsItsHttpCall() = runBlocking {
        val started = CompletableDeferred<Call>()
        val stopped = CompletableDeferred<Unit>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            started.complete(chain.call())
            try {
                while (!chain.call().isCanceled()) Thread.sleep(5)
                throw IOException("Canceled")
            } finally { stopped.complete(Unit) }
        }.build()
        val work = async { FirstPartyAddonAuth.call(request, client) }
        try {
            val call = withTimeout(2_000) { started.await() }
            withTimeout(2_000) { work.cancelAndJoin() }
            assertTrue(call.isCanceled())
            withTimeout(2_000) { stopped.await() }
        } finally { work.cancelAndJoin(); client.dispatcher.executorService.shutdown() }
    }

    @Test fun verificationResponsesStillValidateStatusAndJson() = runBlocking {
        fun client(code: Int, body: String) = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Reply")
                .body(body.toResponseBody()).build()
        }.build()
        val good = client(200, """{"ok":true}""")
        val refused = client(403, """{"code":"APP_SIGNER_MISMATCH"}""")
        val invalid = client(200, "not json")
        try {
            assertTrue(FirstPartyAddonAuth.call(request, good).getBoolean("ok"))
            val denied = runCatching { FirstPartyAddonAuth.call(request, refused) }.exceptionOrNull()
            assertTrue(denied is FirstPartyAddonAuth.VerificationFailure)
            assertTrue(denied!!.message!!.contains("APP_SIGNER_MISMATCH"))
            assertTrue(runCatching { FirstPartyAddonAuth.call(request, invalid) }.exceptionOrNull() is IOException)
        } finally { listOf(good, refused, invalid).forEach { it.dispatcher.executorService.shutdown() } }
    }
}
