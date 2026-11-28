// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27)
public final class VerifiedVideoDownloadTest {
    private static final String HOST = "media.example.org";
    private static final URI RENDITION = URI.create("https://media.example.org/video.mp4");
    private static final String SHA = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    @Rule public TemporaryFolder storage = new TemporaryFolder();

    @Test public void requiresTheExactHttpsOrigin() {
        assertTrue(VerifiedVideoDownload.accepts(RENDITION, HOST));
        for (String uri : new String[]{"http://media.example.org/video.mp4", "https://media.example.org.evil.test/video.mp4",
                "https://user@media.example.org/video.mp4", "https://media.example.org@evil.test/video.mp4",
                "https://media.example.org:8080/video.mp4", "https://media.example.org/video.mp4#fragment"}) {
            assertFalse(uri, VerifiedVideoDownload.accepts(URI.create(uri), HOST));
        }
    }

    @Test public void cachedFileMustMatchTheDeclaredBytesAndLimits() throws Exception {
        File cache = storage.newFolder();
        File staged = new File(cache, "video-" + SHA + ".mp4");
        Files.write(staged.toPath(), "abc".getBytes(StandardCharsets.UTF_8));
        assertEquals(staged, VerifiedVideoDownload.fetch(cache, RENDITION, HOST, 3, SHA));
        assertThrows(IOException.class, () -> VerifiedVideoDownload.fetch(cache, RENDITION, HOST,
                VerifiedVideoDownload.MAX_BYTES + 1, SHA));
    }

    @Test public void rejectsTruncationExtraBytesChangedContentAndExpiredWork() throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 1000;
        VerifiedVideoDownload.verify(bytes("abc"), null, 3, SHA, deadline);
        for (String bad : new String[]{"ab", "abcd", "abd"}) {
            assertThrows(IOException.class, () -> VerifiedVideoDownload.verify(bytes(bad), null, 3, SHA, deadline));
        }
        assertThrows(IOException.class, () -> VerifiedVideoDownload.verify(bytes("abc"), null, 3, SHA,
                SystemClock.elapsedRealtime()));
    }

    private static ByteArrayInputStream bytes(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
