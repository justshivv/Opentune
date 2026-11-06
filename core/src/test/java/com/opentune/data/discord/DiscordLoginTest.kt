package com.opentune.data.discord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Discord's answers to a sign-in, read as the next step. */
@RunWith(RobolectricTestRunner::class)
class DiscordLoginTest {
    @Test fun aTokenIsDone() {
        assertEquals(DiscordLogin.Step.Done("abc.def.ghi"), DiscordLogin.parse(200, """{"user_id":"1","token":"abc.def.ghi","user_settings":{}}"""))
    }

    @Test fun aCaptchaIsAsked() {
        val step = DiscordLogin.parse(400, """{"captcha_key":["captcha-required"],"captcha_sitekey":"site","captcha_service":"hcaptcha","captcha_session_id":"s1","captcha_rqdata":"rq","captcha_rqtoken":"tok"}""")
        assertEquals(DiscordLogin.Step.Captcha("site", "rq", "tok", "s1"), step)
    }

    @Test fun twoFactorIsAsked() {
        val step = DiscordLogin.parse(200, """{"token":null,"mfa":true,"sms":true,"ticket":"T","backup":true,"totp":true,"webauthn":null}""")
        assertEquals(DiscordLogin.Step.TwoFactor("T", totp = true, sms = true, backup = true), step)
    }

    @Test fun wrongPasswordSaysSo() {
        val step = DiscordLogin.parse(400, """{"message":"Invalid Form Body","code":50035,"errors":{"login":{"_errors":[{"code":"INVALID_LOGIN","message":"Login or password is invalid."}]}}}""")
        assertEquals(DiscordLogin.Step.Failed("That email or password isn't right."), step)
    }

    @Test fun aNewPlaceAsksForTheEmail() {
        val step = DiscordLogin.parse(400, """{"message":"Invalid Form Body","code":50035,"errors":{"login":{"_errors":[{"code":"ACCOUNT_LOGIN_VERIFICATION_EMAIL","message":"New login location detected, please check your e-mail."}]}}}""")
        assertTrue((step as DiscordLogin.Step.Failed).message.contains("emailed"))
    }

    @Test fun tooManyTries() {
        val step = DiscordLogin.parse(429, """{"message":"You are being rate limited.","retry_after":12.5,"global":false}""")
        assertTrue((step as DiscordLogin.Step.Failed).message.contains("12 s"))
    }
}
