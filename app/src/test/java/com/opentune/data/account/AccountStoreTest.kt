package com.opentune.data.account

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AccountStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)

    @After fun tearDown() = AccountStore.signOut()

    @Test
    fun savedProfileShowsAsSoonAsTheAppStarts() {
        // What a signed-in run leaves behind; the next start may have no network yet.
        prefs.edit().putString("cookie", "SAPISID=abc; HSID=def")
            .putString("profile_name", "Shiv").putString("profile_email", "shiv@example.com")
            .putString("profile_photo", "https://example.com/me.jpg").commit()
        AccountStore.init(context)
        assertTrue(AccountStore.signedIn.value)
        assertEquals("Shiv", AccountStore.account.value?.name)
        assertEquals("shiv@example.com", AccountStore.account.value?.email)
    }

    @Test
    fun signingOutForgetsTheSavedProfile() {
        prefs.edit().putString("cookie", "SAPISID=abc").putString("profile_name", "Shiv").commit()
        AccountStore.init(context)
        AccountStore.signOut()
        assertNull(AccountStore.account.value)
        assertNull(prefs.getString("profile_name", null))
    }
}
