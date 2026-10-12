// SPDX-License-Identifier: Apache-2.0
package kioskvoip.spike;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import kioskvoip.sip.SipClient;

/**
 * Spike: register one extension, auto-answer pages, save each as a 16 kHz WAV in out/.
 * Settings come from the environment only: SIP_SERVER, SIP_USER, SIP_PASSWORD, optional SIP_AUTH_USER,
 * SIP_PORT (5060), SIP_LOCAL_PORT (5070). Runs until killed.
 */
public final class PageToWav {
    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        if (value != null && !value.isEmpty()) return value;
        if (fallback == null) throw new IllegalArgumentException("Set " + name);
        return fallback;
    }

    private static void event(String text) {
        System.out.println(LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")) + " " + text);
    }

    public static void main(String[] args) throws Exception {
        SipClient.Config config = new SipClient.Config(
            env("SIP_SERVER", null), Integer.parseInt(env("SIP_PORT", "5060")), env("SIP_USER", null),
            env("SIP_AUTH_USER", ""), env("SIP_PASSWORD", null), Integer.parseInt(env("SIP_LOCAL_PORT", "5070")), 300);
        Path outDir = Paths.get("out");
        Files.createDirectories(outDir);
        ByteArrayOutputStream[] page = new ByteArrayOutputStream[1];

        SipClient client = new SipClient(config, new SipClient.Listener() {
            @Override public void onRegistration(boolean registered, String detail) {
                event((registered ? "registered: " : "not registered: ") + detail);
            }
            @Override public SipClient.Answer onInvite(String caller, String codec) {
                event("invite from " + caller + " codec " + codec + " → answer");
                ByteArrayOutputStream pcm = new ByteArrayOutputStream();
                synchronized (page) { page[0] = pcm; }
                return SipClient.Answer.accept(samples -> {
                    byte[] bytes = new byte[samples.length * 2];
                    for (int i = 0; i < samples.length; i++) {
                        bytes[2 * i] = (byte) samples[i];
                        bytes[2 * i + 1] = (byte) (samples[i] >> 8);
                    }
                    synchronized (pcm) { pcm.write(bytes, 0, bytes.length); }
                });
            }
            @Override public void onCallStarted(String caller) { event("answered " + caller); }
            @Override public void onCallEnded(String reason, long packets, long dropped) {
                ByteArrayOutputStream pcm;
                synchronized (page) { pcm = page[0]; page[0] = null; }
                if (pcm == null) return;
                byte[] data;
                synchronized (pcm) { data = pcm.toByteArray(); }
                Path file = outDir.resolve("page-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".wav");
                try (OutputStream out = Files.newOutputStream(file)) {
                    writeWav(out, data, 16000);
                    event(String.format("ended (%s): %d packets, %d dropped, %.1f s → %s",
                        reason, packets, dropped, data.length / 32000.0, file));
                } catch (IOException error) {
                    event("ended (" + reason + "), could not write WAV: " + error.getMessage());
                }
            }
            @Override public void log(String message) { event(message); }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(client::stop));
        client.start();
        event("started; Ctrl-C to stop");
        Thread.currentThread().join();
    }

    static void writeWav(OutputStream out, byte[] pcm, int rate) throws IOException {
        java.nio.ByteBuffer h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes())
         .put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
         .putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16)
         .put("data".getBytes()).putInt(pcm.length);
        out.write(h.array());
        out.write(pcm);
    }
}
