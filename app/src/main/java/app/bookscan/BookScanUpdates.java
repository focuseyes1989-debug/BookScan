package app.bookscan;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BookScanUpdates {
    private static final String REPOSITORY = "focuseyes1989-debug/BookScan";
    private static final Pattern APK_NAME = Pattern.compile("BookScan-([1-9][0-9]{0,8})\\.apk");
    private static final Pattern TAG = Pattern.compile("v[0-9]+(?:\\.[0-9]+){1,2}");

    static final class Release {
        final String tag;
        final Uri apk;

        Release(String tag, Uri apk) {
            this.tag = tag;
            this.apk = apk;
        }
    }

    static Release findNewer(int installedVersion) throws IOException, JSONException {
        URL endpoint = new URL("https://api.github.com/repos/" + REPOSITORY + "/releases/latest");
        HttpURLConnection request = (HttpURLConnection) endpoint.openConnection();
        request.setConnectTimeout(8000);
        request.setReadTimeout(8000);
        request.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            int status = request.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_FOUND) return null;
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("GitHub HTTP " + status);

            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    request.getInputStream(), StandardCharsets.UTF_8))) {
                char[] chunk = new char[4096];
                int length;
                while ((length = reader.read(chunk)) != -1) {
                    body.append(chunk, 0, length);
                    if (body.length() > 512_000) throw new IOException("Release response too large");
                }
            }
            return parseRelease(new JSONObject(body.toString()), installedVersion);
        } finally {
            request.disconnect();
        }
    }

    static Release parseRelease(JSONObject release, int installedVersion) throws JSONException {
        String tag = release.getString("tag_name");
        if (!TAG.matcher(tag).matches()) return null;
        JSONArray assets = release.getJSONArray("assets");
        Release latest = null;
        int newest = installedVersion;
        for (int index = 0; index < assets.length(); index++) {
            JSONObject asset = assets.getJSONObject(index);
            String name = asset.getString("name");
            Matcher match = APK_NAME.matcher(name);
            if (!match.matches() || !"uploaded".equals(asset.optString("state"))) continue;
            int version = Integer.parseInt(match.group(1));
            String expected = "https://github.com/" + REPOSITORY + "/releases/download/" + tag + "/" + name;
            if (version > newest && expected.equals(asset.optString("browser_download_url"))) {
                newest = version;
                latest = new Release(tag, Uri.parse(expected));
            }
        }
        return latest;
    }

    private BookScanUpdates() {}
}
