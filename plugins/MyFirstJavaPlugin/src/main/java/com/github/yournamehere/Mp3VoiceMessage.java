package com.github.yournamehere;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.widget.Toast;
import com.aliucord.Logger;
import com.aliucord.Utils;
import com.aliucord.Http;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.api.CommandsAPI.CommandResult;
import com.aliucord.entities.Plugin;
import com.discord.api.commands.ApplicationCommandType;
import com.discord.stores.StoreStream;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.Executors;

@AliucordPlugin
public class Mp3VoiceMessage extends Plugin {
    private final Logger logger = new Logger("Mp3VoiceMessage");
    private static final int VOICE_MESSAGE_FLAG = 8192;
    private static final String WAVEFORM_PLACEHOLDER = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Override
    public void start(Context ctx) throws Throwable {
        commands.registerCommand(
            "sendmp3",
            "Send an MP3 as a voice message",
            Collections.singletonList(Utils.createCommandOption(ApplicationCommandType.STRING, "url", "Direct URL to MP3")),
            ctx2 -> {
                String mp3Url = ctx2.getRequiredString("url");
                long channelId = StoreStream.getChannelsSelected().getId();
                if (channelId == 0) return new CommandResult("Could not determine current channel.", null, false);
                Executors.newSingleThreadExecutor().execute(() -> {
                    try { sendMp3AsVoiceMessage(ctx, mp3Url, channelId); }
                    catch (Exception e) {
                        logger.error("Failed", e);
                        Utils.mainThread.post(() -> Toast.makeText(ctx, "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                    }
                });
                return new CommandResult("Sending MP3 as voice message...", null, false);
            }
        );
    }

    private void sendMp3AsVoiceMessage(Context ctx, String mp3Url, long channelId) throws Exception {
        byte[] mp3Bytes = downloadBytes(mp3Url);
        if (mp3Bytes == null || mp3Bytes.length == 0) throw new Exception("Failed to download MP3");
        long durationMs = getDurationMs(ctx, mp3Bytes);
        double durationSecs = durationMs > 0 ? durationMs / 1000.0 : 1.0;
        String filename = "voice-message.ogg";

        JSONObject attachmentBody = new JSONObject();
        attachmentBody.put("id", "0");
        attachmentBody.put("filename", filename);
        attachmentBody.put("waveform", WAVEFORM_PLACEHOLDER);
        attachmentBody.put("duration_secs", durationSecs);
        JSONArray attachments = new JSONArray();
        attachments.put(attachmentBody);
        JSONObject payload = new JSONObject();
        payload.put("flags", VOICE_MESSAGE_FLAG);
        payload.put("channel_id", String.valueOf(channelId));
        payload.put("content", "");
        payload.put("nonce", String.valueOf((System.currentTimeMillis() - 1420070400000L) << 22));
        payload.put("type", 0);
        payload.put("attachments", attachments);

        String boundary = "----WebKitFormBoundary" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n" + payload + "\r\n").getBytes());
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\"" + filename + "\"\r\nContent-Type: audio/mpeg\r\n\r\n").getBytes());
        body.write(mp3Bytes);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes());

        var req = new Http.Request("https://discord.com/api/v9/channels/" + channelId + "/messages", "POST")
            .setHeader("Content-Type", "multipart/form-data; boundary=" + boundary);
        req.conn.setDoOutput(true);
        req.conn.getOutputStream().write(body.toByteArray());
        var res = req.execute();
        int code = res.statusCode;
        if (code == 200 || code == 201)
            Utils.mainThread.post(() -> Toast.makeText(ctx, "Voice message sent!", Toast.LENGTH_SHORT).show());
        else
            throw new Exception("HTTP " + code + ": " + res.text());
    }

    private byte[] downloadBytes(String urlStr) throws Exception {
        return new Http.Request(urlStr, "GET").execute().binaryBody;
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
