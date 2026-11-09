package app.buddy.assistant;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * What happens to a voice command: done at once, or not at all. Understood offline (CommandParser) → LocalCommands
 * does it right away, network or not. Otherwise it goes to Claude Code, only if there is a network and a connection to
 * Claude comes up within 15 seconds; if the connection drops while it works, the run is stopped. Nothing is queued,
 * sent later or tried again, and a cancelled command isn't kept: the user hears and sees "No network, command not
 * done: …" instead.
 */
final class VoiceCommand {
    /** What the voice screen does for a command: speak and show, let the user pick, confirm. */
    interface Host {
        Activity activity();

        /** The language of the command: ru-RU, en-US or ar-EG. */
        String lang();

        /** Shows a line and says it. */
        void say(String text);

        /** Shows the options; pick gets the chosen one's index ("0", "1"…), nothing if dismissed. */
        void choose(String question, List<String> options, Ui.Pick pick);

        /** Buddy's confirmation card; yes runs only if the user approves. */
        void confirm(String title, String message, Runnable yes);
    }

    /** A voice chat is used again for a new command only this soon after the last one (and only if it's free). */
    static final long SAME_CHAT_MS = 5 * 60_000L;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private VoiceCommand() {
    }

    /** Buddy's strings in the language of the command (falls back to English where there's no translation). */
    static String t(Context c, String lang, int id, Object... args) {
        Configuration conf = new Configuration(c.getResources().getConfiguration());
        conf.setLocale(Locale.forLanguageTag(lang));
        return c.createConfigurationContext(conf).getResources().getString(id, args);
    }

    static void handle(Host h, String text) {
        Context app = h.activity().getApplicationContext();
        Command c = CommandParser.parse(text, h.lang());
        if (c != null && LocalCommands.run(h, c)) return;
        if (!Net.online(app)) {
            cancelled(app, h.lang(), text, h);
            return;
        }
        h.say(t(app, h.lang(), R.string.vc_to_claude));
        new Thread(() -> {
            boolean up = Net.reaches(Net.CLAUDE_HOST, Net.CONNECT_MS);
            main.post(() -> {
                if (!up) cancelled(app, h.lang(), text, h);
                else toClaude(h, text);
            });
        }, "voice-connect").start();
    }

    /** "No network, command not done: …", said and as a short notification. The command isn't kept anywhere. */
    static void cancelled(Context app, String lang, String text, Host h) {
        String msg = t(app, lang, R.string.vc_no_network, text);
        if (h != null && !h.activity().isFinishing()) h.say(msg);
        else Speaker.say(app, msg, lang);
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("voice", app.getString(R.string.vc_channel),
                NotificationManager.IMPORTANCE_DEFAULT));
        nm.notify(7301, new android.app.Notification.Builder(app, "voice").setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(app.getString(R.string.app_name)).setContentText(msg)
                .setStyle(new android.app.Notification.BigTextStyle().bigText(msg)).setAutoCancel(true).build());
    }

    // ------------------------------------------------------------- Claude

    private static void toClaude(Host h, String text) {
        Context app = h.activity().getApplicationContext();
        String lang = h.lang();
        if (!Linux.installed(app) || !SetupState.signedIn(app, "claude")) {
            h.say(t(app, lang, R.string.vc_no_claude));
            return;
        }
        ChatHub hub = ChatHub.get(app);
        Sessions.Session ses = hub.sessions.find(VoicePrefs.chat(app));
        long now = System.currentTimeMillis();
        boolean reuse = ses != null && !ses.running && "claude".equals(ses.backend)
                && now - VoicePrefs.chatAt(app) <= SAME_CHAT_MS;
        boolean created = !reuse;
        if (!reuse) ses = hub.create("claude", false);
        // buddy-run: no automatic retries for a voice run (CLAUDE_CODE_MAX_RETRIES=0)
        File marker = new File(Linux.root(app), "root/.buddy/sessions/" + ses.id + "/voice");
        try {
            File dir = marker.getParentFile();
            if (dir != null) dir.mkdirs();
            marker.createNewFile();
        } catch (Exception ignored) {
        }
        int before = ses.messages.size();
        String err = hub.send(ses, text);
        if (err != null) {
            if (created) hub.delete(ses);
            h.say(err);
            return;
        }
        VoicePrefs.setChat(app, ses.id, now);
        new Watch(h, ses, text, created, before).start();
    }

    /** Watches a voice run: speaks the replies, and stops it (no retry) if the connection goes. */
    private static final class Watch implements ChatHub.Listener {
        final Host h;
        final Context app;
        final Sessions.Session ses;
        final String text, lang;
        final boolean created;
        final int before;
        final long sentAt;
        boolean over;
        ConnectivityManager.NetworkCallback net;

        Watch(Host h, Sessions.Session ses, String text, boolean created, int before) {
            this.h = h;
            this.app = h.activity().getApplicationContext();
            this.ses = ses;
            this.text = text;
            this.lang = h.lang();
            this.created = created;
            this.before = before;
            this.sentAt = ses.lastEventAt;
        }

        void start() {
            ChatHub.get(app).addListener(this);
            net = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onLost(Network n) { // a handover to another network is fine; none left is not
                    main.postDelayed(() -> {
                        if (!over && ses.running && !Net.online(app)) drop();
                    }, 1500);
                }
            };
            try {
                app.getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(net);
            } catch (Exception e) {
                net = null;
            }
            // the agent has to start talking within 15 s (it's local: its first word comes before Claude's)
            main.postDelayed(() -> {
                if (!over && ses.running && ses.lastEventAt <= sentAt) drop();
            }, Net.CONNECT_MS);
        }

        private void end() {
            if (over) return;
            over = true;
            ChatHub.get(app).removeListener(this);
            if (net != null) {
                try {
                    app.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(net);
                } catch (Exception ignored) {
                }
            }
        }

        /** The connection went: stop, say so, and keep nothing of the command. */
        private void drop() {
            if (over) return;
            end();
            ChatHub hub = ChatHub.get(app);
            if (ses.running) hub.stop(ses);
            forget();
            cancelled(app, lang, text, h);
        }

        /** The cancelled command leaves no trace: its new chat goes, or its messages leave the reused one. */
        private void forget() {
            ChatHub hub = ChatHub.get(app);
            VoicePrefs.setChat(app, null, 0); // and the next command starts a new chat, never this one's context
            if (created) {
                main.postDelayed(() -> hub.delete(ses), 500);
            } else {
                while (ses.messages.size() > before) ses.messages.remove(ses.messages.size() - 1);
                hub.sessions.save(ses);
                hub.changed();
            }
        }

        @Override
        public void onChatsChanged() {
            if (!over && !ses.running && ses.lastEventAt > sentAt) {
                end();
                VoicePrefs.setChat(app, ses.id, System.currentTimeMillis());
            }
        }

        @Override
        public void onMessage(Sessions.Session s, Sessions.Msg m) {
            if (over || s != ses) return;
            if (m.kind == Bubble.Kind.BUDDY) {
                main.post(() -> sayReply(m.text));
            } else if (m.kind == Bubble.Kind.ERROR) {
                if (networkish(m.text)) main.post(this::drop);
                else main.post(() -> sayReply(m.text));
            }
        }

        private void sayReply(String reply) {
            if (h.activity().isFinishing()) Speaker.say(app, Speaker.clean(reply), lang);
            else h.say(Speaker.clean(reply));
        }
    }

    /** An agent error that means the connection to Claude failed or dropped. */
    static boolean networkish(String s) {
        String l = s == null ? "" : s.toLowerCase(Locale.ROOT);
        for (String k : new String[]{"connect", "network", "econn", "enotfound", "etimedout", "eai_again", "fetch failed",
                "socket", "timed out", "timeout", "dns", "unreachable", "offline", "соедин", "сеть", "сети"}) {
            if (l.contains(k)) return true;
        }
        return false;
    }
}
