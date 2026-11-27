// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WebSession {
    static final WebSession EMPTY = new WebSession(null, null, "", "");

    final Integer index;
    final String pageId, visitor, version;

    private WebSession(Integer index, String pageId, String visitor, String version) {
        this.index = index;
        this.pageId = pageId;
        this.visitor = visitor;
        this.version = version;
    }

    static WebSession fromWebView(String result, String authUser) {
        JSONObject values;
        try {
            values = new JSONObject(result == null ? "{}" : result);
        } catch (JSONException invalid) {
            values = new JSONObject();
        }
        String index = values.optString("index");
        return read(index.matches("[0-9]{1,6}") ? index : authUser, values.optString("sync"),
                values.optString("visitor"), values.optString("version"));
    }

    static WebSession fromLandingPage(String page) {
        String sync = field(page, "DATASYNC_ID", false);
        if (sync.isEmpty()) sync = field(page, "datasyncId", false);
        return read(field(page, "SESSION_INDEX", true), sync, field(page, "VISITOR_DATA", false),
                field(page, "INNERTUBE_CLIENT_VERSION", false));
    }

    private static WebSession read(String index, String sync, String visitor, String version) {
        Integer number = index != null && index.matches("[0-9]{1,6}") ? Integer.valueOf(index) : null;
        String page = null;
        String[] ids = sync.split("\\|\\|", -1);
        if (ids.length == 2 && ids[0].matches("[a-zA-Z0-9_-]{1,128}")
                && ids[1].matches("[a-zA-Z0-9_-]{0,128}")) {
            page = ids[1].isEmpty() ? "" : ids[0];
        }
        return new WebSession(number, page,
                visitor.matches("[a-zA-Z0-9%_=.\\-+]{1,2048}") ? visitor : "",
                version.matches("[0-9.]{1,32}") ? version : "");
    }

    private static String field(String page, String key, boolean numeric) {
        String value = numeric ? "(\"[0-9]{1,6}\"|[0-9]{1,6})(?=\\s*[,}])" : "\"([^\"\\p{Cntrl}]{1,4096})\"";
        Matcher match = Pattern.compile("\"" + key + "\"\\s*:\\s*" + value).matcher(page);
        if (!match.find()) return "";
        return numeric ? match.group(1).replace("\"", "") : match.group(1);
    }

    WebSession fillFrom(WebSession landing) {
        // A landing page for another Google account cannot supply this account's channel or visitor.
        boolean sameAccount = index == null || index.equals(landing.index);
        boolean sameChannel = sameAccount && (pageId == null || pageId.equals(landing.pageId));
        return new WebSession(index == null ? landing.index : index,
                pageId == null && sameAccount ? landing.pageId : pageId,
                visitor.isEmpty() && sameChannel ? landing.visitor : visitor,
                landing.version.isEmpty() ? version : landing.version);
    }

    boolean hasRequestContext() {
        return index != null && pageId != null && !visitor.isEmpty() && !version.isEmpty();
    }
}
