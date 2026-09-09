package com.scrolller.adblock;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Removes the legacy giant hiddenPosts SharedPreferences value before Android's
 * SharedPreferences implementation loads that XML file into the Java heap.
 *
 * Older builds rewrote the complete RedditPost JSON history after each swipe.
 * Once that value grows to tens of MB, merely loading/serializing it can exhaust
 * a 256 MB app heap. This class performs a streaming one-time rewrite of the XML
 * file and preserves the post IDs in ReadHideStore without materializing the
 * giant JSON string.
 */
final class LegacyHiddenPrefsPreflight {
    private static final long MIN_PREF_SIZE = 4L * 1024L * 1024L;
    private static final byte[] START =
            "<string name=\"hiddenPosts\">".getBytes(StandardCharsets.UTF_8);
    private static final byte[] END = "</string>".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ID_PREFIX = "\"id\":\"".getBytes(StandardCharsets.UTF_8);

    private LegacyHiddenPrefsPreflight() {}

    static void migrateIfNeeded(Context context, ReadHideStore store) {
        if (context == null || store == null) return;
        try {
            File prefsDir = new File(context.getApplicationInfo().dataDir, "shared_prefs");
            File source = new File(prefsDir, "native-redview.xml");
            if (!source.isFile() || source.length() < MIN_PREF_SIZE) return;

            File temp = new File(prefsDir, "native-redview.xml.readhide.tmp");
            File backup = new File(prefsDir, "native-redview.xml.readhide.bak");
            if (temp.exists()) temp.delete();

            LinkedHashSet<String> ids = new LinkedHashSet<>();
            boolean removed = rewriteWithoutLegacyHiddenPosts(source, temp, ids);
            if (!removed) {
                temp.delete();
                return;
            }

            // Persist IDs before replacing the preference file. If the final rename
            // fails, duplicate ID inserts are harmless because the DB key is unique.
            store.importIds(ids);

            if (backup.exists()) backup.delete();
            if (!source.renameTo(backup)) {
                temp.delete();
                return;
            }
            if (!temp.renameTo(source)) {
                backup.renameTo(source);
                temp.delete();
                return;
            }
            backup.delete();
        } catch (Throwable ignored) {
            // Startup must never fail because a best-effort legacy cleanup failed.
        }
    }

    private static boolean rewriteWithoutLegacyHiddenPosts(
            File source,
            File temp,
            Set<String> ids) throws IOException {
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(source), 64 * 1024);
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(temp), 64 * 1024)) {

            if (!copyUntilSequence(in, out, START)) return false;

            int endMatched = 0;
            int idMatched = 0;
            boolean readingId = false;
            StringBuilder id = new StringBuilder(32);
            int value;

            while ((value = in.read()) != -1) {
                byte b = (byte) value;

                if (readingId) {
                    if (b == '"') {
                        if (id.length() > 0 && id.length() <= 128) ids.add(id.toString());
                        id.setLength(0);
                        readingId = false;
                        idMatched = 0;
                    } else if (id.length() < 128 && b >= 0x20 && b < 0x7f) {
                        id.append((char) b);
                    } else {
                        id.setLength(0);
                        readingId = false;
                        idMatched = 0;
                    }
                } else {
                    if (b == ID_PREFIX[idMatched]) {
                        idMatched++;
                        if (idMatched == ID_PREFIX.length) {
                            readingId = true;
                            id.setLength(0);
                            idMatched = 0;
                        }
                    } else {
                        idMatched = b == ID_PREFIX[0] ? 1 : 0;
                    }
                }

                if (b == END[endMatched]) {
                    endMatched++;
                    if (endMatched == END.length) break;
                } else {
                    endMatched = b == END[0] ? 1 : 0;
                }
            }

            if (endMatched != END.length) return false;

            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            out.flush();
            return true;
        }
    }

    /** Writes all input bytes before {@code sequence}, but not the sequence itself. */
    private static boolean copyUntilSequence(
            BufferedInputStream in,
            BufferedOutputStream out,
            byte[] sequence) throws IOException {
        byte[] pending = new byte[sequence.length];
        int matched = 0;
        int value;

        while ((value = in.read()) != -1) {
            byte b = (byte) value;
            if (b == sequence[matched]) {
                pending[matched++] = b;
                if (matched == sequence.length) return true;
                continue;
            }

            if (matched > 0) {
                out.write(pending, 0, matched);
                matched = 0;
                if (b == sequence[0]) {
                    pending[matched++] = b;
                    continue;
                }
            }
            out.write(b);
        }

        if (matched > 0) out.write(pending, 0, matched);
        out.flush();
        return false;
    }
}
