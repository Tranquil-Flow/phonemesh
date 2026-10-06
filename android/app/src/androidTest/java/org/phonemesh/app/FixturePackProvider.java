package org.phonemesh.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Test-only provider (in the test APK's own process, so plain Java: no Kotlin runtime there).
 * {@code content://org.phonemesh.app.test.fixtures/voice.zip} is a fixture voice pack with id
 * voice-fr-siwis; it installs only where unknown packs are allowed (debug builds).
 */
public class FixturePackProvider extends ContentProvider {
    public static final String VOICE = "content://org.phonemesh.app.test.fixtures/voice.zip";

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) { return "application/zip"; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"/voice.zip".equals(uri.getPath()) || !"r".equals(mode)) throw new FileNotFoundException(uri.toString());
        try {
            File file = new File(getContext().getCacheDir(), "voice.zip");
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(voice()); }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new FileNotFoundException(e.toString());
        }
    }

    private static byte[] voice() throws IOException, NoSuchAlgorithmException {
        Map<String, String> files = new TreeMap<>();
        files.put("pack.properties", "format=2\nkind=voice\nid=voice-fr-siwis\nversion=1\nlabel=Français\nlanguageTag=fr\nwhisperLanguage=fr\n");
        for (String name : new String[] {"tts/model.onnx", "tts/tokens.txt", "tts/espeak-ng-data/phontab",
                "tts/espeak-ng-data/phondata", "tts/espeak-ng-data/phonindex", "tts/espeak-ng-data/intonations",
                "tts/espeak-ng-data/lang/roa/fr"}) {
            files.put(name, "fixture");
        }
        StringBuilder sums = new StringBuilder();
        for (Map.Entry<String, String> e : files.entrySet()) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(e.getValue().getBytes(StandardCharsets.UTF_8));
            for (byte b : digest) sums.append(String.format("%02x", b & 0xff));
            sums.append("  ").append(e.getKey()).append('\n');
        }
        files.put("SHA256SUMS", sums.toString());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> e : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
