package app.buddy.assistant;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Is there a network, and does Claude's server answer: the voice assistant's "now or not at all" (VoiceCommand). */
final class Net {
    static final String CLAUDE_HOST = "api.anthropic.com";
    /** How long a connection to Claude may take to come up before a command is given up. */
    static final int CONNECT_MS = 15_000;

    private Net() {
    }

    /** A network Android has checked reaches the internet. */
    static boolean online(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        if (cm == null) return false;
        Network n = cm.getActiveNetwork();
        NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    /**
     * Whether a TLS connection to host:443 comes up within timeoutMs, name lookup included (off the main thread).
     * Only the connection: nothing is sent.
     */
    static boolean reaches(String host, int timeoutMs) {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> f = ex.submit(() -> {
                try (SSLSocket s = (SSLSocket) SSLSocketFactory.getDefault().createSocket()) {
                    s.connect(new InetSocketAddress(host, 443), timeoutMs);
                    s.setSoTimeout(timeoutMs);
                    s.startHandshake();
                    return true;
                }
            });
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return false;
        } finally {
            ex.shutdownNow();
        }
    }
}
