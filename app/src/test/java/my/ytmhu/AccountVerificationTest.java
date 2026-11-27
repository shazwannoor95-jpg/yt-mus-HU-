// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public final class AccountVerificationTest {
    @Test public void readsModernAndLegacyLandingPageFields() {
        WebSession selected = WebSession.fromLandingPage("ytcfg.set({ \"SESSION_INDEX\": 2,"
                + "\"DATASYNC_ID\":\"channel||user\", \"VISITOR_DATA\":\"fixture%3D\","
                + "\"INNERTUBE_CLIENT_VERSION\":\"1.20261101.00.00\" });");
        assertEquals(Integer.valueOf(2), selected.index);
        assertEquals("channel", selected.pageId);
        assertEquals("fixture%3D", selected.visitor);
        assertTrue(selected.hasRequestContext());

        WebSession main = WebSession.fromLandingPage("{\"SESSION_INDEX\":\"0\",\"datasyncId\":\"user||\"}");
        assertEquals(Integer.valueOf(0), main.index);
        assertEquals("", main.pageId);
    }

    @Test public void usesBrowserAccountAndDoesNotMixTwoAccounts() {
        WebSession browser = WebSession.fromWebView("{\"sync\":\"browser-channel||user\"}", "2");
        WebSession other = WebSession.fromLandingPage("{\"SESSION_INDEX\":0,"
                + "\"datasyncId\":\"native-channel||user\",\"VISITOR_DATA\":\"other\"}");
        WebSession combined = browser.fillFrom(other);
        assertEquals(Integer.valueOf(2), combined.index);
        assertEquals("browser-channel", combined.pageId);
        assertEquals("", combined.visitor);
        assertNull(WebSession.fromWebView("{\"index\":\"2.4\",\"sync\":\"invalid ID||user\"}", null).pageId);
        assertNull(WebSession.fromLandingPage("{\"SESSION_INDEX\":20.4}").index);
    }

    @Test public void acceptsMenuHeaderWithoutAnExtraRequest() throws Exception {
        assertEquals("Test account", MusicApi.verifyAccount((endpoint, body) -> {
            assertEquals("account/account_menu", endpoint);
            return new JSONObject("{\"actions\":[{\"activeAccountHeaderRenderer\":"
                    + "{\"accountName\":{\"runs\":[{\"text\":\"Test account\"}]}}}]}");
        }));
    }

    @Test public void missingHeaderFallsBackToExplicitAuthenticatedBrowse() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        assertEquals("YouTube account", MusicApi.verifyAccount((endpoint, body) -> {
            if (requests.incrementAndGet() == 1) return new JSONObject();
            assertEquals("browse", endpoint);
            assertEquals("FEmusic_home", body.getString("browseId"));
            return new JSONObject("{\"responseContext\":{\"serviceTrackingParams\":[{\"params\":["
                    + "{\"key\":\"logged_in\",\"value\":\"1\"}]}]}}");
        }));
        assertEquals(2, requests.get());
        assertEquals("YouTube account", MusicApi.verifyAccount((endpoint, body) -> {
            assertEquals("account/account_menu", endpoint);
            return new JSONObject("{\"responseContext\":{\"mainAppWebResponseContext\":{\"loggedOut\":false}}}");
        }));
    }

    @Test public void rejectsSignedOutAndResponsesWithoutSessionEvidence() {
        MusicApi.AccountFailure signedOut = assertThrows(MusicApi.AccountFailure.class,
                () -> MusicApi.verifyAccount((endpoint, body) -> {
                    assertEquals("account/account_menu", endpoint);
                    return new JSONObject("{\"responseContext\":{\"mainAppWebResponseContext\":{\"loggedOut\":true}},"
                            + "\"activeAccountHeaderRenderer\":{}}");
                }));
        assertTrue(signedOut.signedOut);
        assertEquals("ACCOUNT_MENU_SIGNED_OUT", signedOut.code);

        MusicApi.AccountFailure unknown = assertThrows(MusicApi.AccountFailure.class,
                () -> MusicApi.verifyAccount((endpoint, body) -> new JSONObject()));
        assertFalse(unknown.signedOut);
        assertEquals("ACCOUNT_RESPONSE_UNRECOGNIZED", unknown.code);
    }

    @Test public void reportsHttpRejectionWithoutRetryOrRawServerDetails() {
        MusicApi.AccountFailure rejected = assertThrows(MusicApi.AccountFailure.class,
                () -> MusicApi.verifyAccount((endpoint, body) -> {
                    assertEquals("account/account_menu", endpoint);
                    throw new MusicApi.HttpStatus(403);
                }));
        assertEquals("ACCOUNT_MENU_HTTP_403", rejected.code);
        assertFalse(rejected.signedOut);

        MusicApi.AccountFailure timeout = assertThrows(MusicApi.AccountFailure.class,
                () -> MusicApi.verifyAccount((endpoint, body) -> {
                    throw new SocketTimeoutException("fixture sensitive response");
                }));
        assertEquals("ACCOUNT_MENU_TIMEOUT", timeout.code);
        assertFalse(timeout.getMessage().contains("sensitive"));
    }
}
