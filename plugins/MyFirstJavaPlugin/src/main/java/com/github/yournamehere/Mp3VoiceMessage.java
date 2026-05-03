package com.github.yournamehere;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.widget.Toast;
import com.aliucord.Logger;
import com.aliucord.Utils;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.api.CommandsAPI;
import com.aliucord.entities.Plugin;
import com.discord.stores.StoreStream;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@AliucordPlugin
public class Mp3VoiceMessage extends Plugin {
    private final Logger logger = new Logger("Mp3VoiceMessage");
    private static final String BASE_URL = "https://discord.com/api/v9";
    private static final int VOICE_MESSAGE_FLAG = 8192;
    private static final String WAVEFORM_PLACEHOLDER = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Override
    public void start(Context ctx) throws Throwable {
        commands.registerCommand("sendmp3", "Send an MP3 as a voice message",
            Collections.singletonList(new CommandsAPI.CommandOption(
                com.discord.api.commands.ApplicationCommandType.STRING,
                "url",
                "Direct URL to MP3",
                null, null, true, false
            )),
            ctx2 -> {
                String mp3Url = ctx2.getRequiredString("url");
                long channelId = StoreStream.getChannelsSelected().getId();
                if (channelId == 0)
                    return new CommandsAPI.CommandResult("Could not determine current channel.", null, false);
                ExecutorService executor = Executors.newSingleThreadExecutor();
                executor.execute(() -> {
                    try { sendMp3AsVoiceMessage(ctx, mp3Url, channelId); }
                    catch (Exception e) {
                        logger.error("Failed", e);
                        Utils.mainThread.post(() -> Toast.makeText(ctx, "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                    }
                });
                return new CommandsAPI.CommandResult("Sending MP3 as voice message...", null, false);
            });
    }

    private void sendMp3AsVoiceMessage(Context ctx, String mp3Url, long channelId) throws Exception {
        byte[] mp3Bytes = downloadBytes(mp3Url);
        if (mp3Bytes == null || mp3Bytes.length == 0) throw new Exception("Failed to download MP3");
        long durationMs = getDurationMs(ctx, mp3Bytes);
        double durationSecs = durationMs > 0 ? durationMs / 1000.0 : 1.0;
        String token = StoreStream.getUsers().getMe().getToken();
        String filename = "voice-message.ogg";
        String endpoint = BASE_URL + "/channels/" + channelId + "/messages";
        String boundary = "----WebKitFormBoundary" + UUID.randomUUID().toString().replace("-", "");
        org.json.JSONObject attachmentBody = new org.json.JSONObject();
        attachmentBody.put("id", "0");
        attachmentBody.put("filename", filename);
        attachmentBody.put("waveform", WAVEFORM_PLACEHOLDER);
        attachmentBody.put("duration_secs", durationSecs);
        org.json.JSONArray attachments = new org.json.JSONArray();
        attachments.put(attachmentBody);
        org.json.JSONObject payload = new org.json.JSONObject();
        payload.put("flags", VOICE_MESSAGE_FLAG);
        payload.put("channel_id", channelId);
        payload.put("content", "");
        payload.put("nonce", String.valueOf((System.currentTimeMillis() - 1420070400000L) << 22));
        payload.put("type", 0);
        payload.put("attachments", attachments);
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", token);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setRequestProperty("User-Agent", "Discord-Android/175207;RNA");
        OutputStream out = conn.getOutputStream();
        String payloadPart = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\n\r\n" + payload + "\r\n";
        out.write(payloadPart.getBytes(StandardCharsets.UTF_8));
        String fileHeader = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\"" + filename + "\"\r\nContent-Type: audio/mpeg\r\n\r\n";
        out.write(fileHeader.getBytes(StandardCharsets.UTF_8));
        out.write(mp3Bytes);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush(); out.close();
        int code = conn.getResponseCode();
        if (code == 200 || code == 201)
            Utils.mainThread.post(() -> Toast.makeText(ctx, "Voice message sent!", Toast.LENGTH_SHORT).show());
        else {
            InputStream err = conn.getErrorStream();
            String body = err != null ? new String(err.readAllBytes(), StandardCharsets.UTF_8) : "no body";
            throw new Exception("HTTP " + code + ": " + body);
        }
    }

    private byte[] downloadBytes(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000); conn.setReadTimeout(30000); conn.connect();
        if (conn.getResponseCode() != 200) throw new Exception("Download failed: HTTP " + conn.getResponseCode());
        try (InputStream in = conn.getInputStream(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int read;
            while ((read = in.read(buf)) != -1) baos.write(buf, 0, read);
            return baos.toByteArray();
        }
    }

    private long getDurationMs(Context ctx, byte[] mp3Bytes) {
        try {
            File tmpFile = File.createTempFile("mp3vm_", ".mp3", ctx.getCacheDir());
            try (FileOutputStream fos = new FileOutputStream(tmpFile)) { fos.write(mp3Bytes); }
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            mmr.setDataSource(tmpFile.getAbsolutePath());
            String dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            mmr.release(); tmpFile.delete();
            return dur != null ? Long.parseLong(dur) : 0;
        } catch (Exception e) { return 0; }
    }

    @Override
    public void stop(Context ctx) { commands.unregisterAll(); }
}
