// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class GoogleSignInTest {
    @Test public void acceptsOnlyTheExpectedFirstPartyRoutes() {
        assertTrue(GoogleSignIn.trustedLoginPage("https://accounts.google.com.my/v3/signin/challenge"));
        assertTrue(GoogleSignIn.trustedLoginPage("https://ACCOUNTS.GOOGLE.CO.UK:443/signin"));
        assertTrue(GoogleSignIn.trustedLoginPage("https://gds.google.com/web/challenge"));
        assertTrue(GoogleSignIn.trustedLoginPage("https://www.google.com.my/accounts/verify"));
        assertTrue(GoogleSignIn.trustedLoginPage("https://g.co/verifyaccount"));
        assertTrue(GoogleSignIn.trustedLoginPage("https://music.youtube.com/?value=a b"));
        assertFalse(GoogleSignIn.trustedLoginPage("https://www.google.com.my/"));
        assertFalse(GoogleSignIn.trustedLoginPage("https://g.co/unrelated"));
        assertFalse(GoogleSignIn.trustedLoginPage("https://www.google.com.my/accounts/../unrelated"));
        assertFalse(GoogleSignIn.trustedLoginPage("https://www.google.com.my/accounts/%2e%2e/unrelated"));
    }

    @Test public void rejectsLookalikesAndUnsafeAuthorities() {
        for (String rejected : new String[] {
                "https://accounts.example.com.my/",
                "https://accounts.google.com.my.example.org/",
                "https://accounts.google.example/",
                "https://accounts.google.com.my@evil.example/",
                "https://evil.example@accounts.google.com.my/",
                "https://accounts.google.com.my:444/",
                "https://accounts.google.com.my./",
                "https://accounts.google.com.my\\@evil.example/",
                "https://accounts.google.com.my%40evil.example/",
                "http://accounts.google.com.my/",
                "//accounts.google.com.my/",
                "javascript:alert(1)",
                "intent://accounts.google.com.my/#Intent;end"
        }) {
            assertFalse(rejected, GoogleSignIn.trustedLoginPage(rejected));
            assertNull(rejected, GoogleSignIn.trustedFallback(rejected));
        }
    }
}
