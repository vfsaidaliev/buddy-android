package app.buddy.assistant;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * Buddy Full's optional Shizuku support, through Shizuku's official API: when Shizuku is installed, running and has
 * allowed Buddy, shell_privileged runs commands with the shell's rights (ShellService, a Shizuku user service).
 * Without it nothing else changes.
 */
final class ShizukuShell {
    static final String PACKAGE = "moe.shizuku.privileged.api";

    enum State { MISSING, STOPPED, OLD, DENIED, READY }

    private static IBinder service;
    private static CountDownLatch connecting;

    private static final ServiceConnection CONN = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder b) {
            synchronized (ShizukuShell.class) {
                service = b != null && b.pingBinder() ? b : null;
                if (connecting != null) connecting.countDown();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (ShizukuShell.class) {
                service = null;
            }
        }
    };

    private ShizukuShell() {
    }

    static State state(Context c) {
        try {
            c.getPackageManager().getPackageInfo(PACKAGE, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return State.MISSING;
        }
        try {
            if (!Shizuku.pingBinder()) return State.STOPPED;
            if (Shizuku.isPreV11()) return State.OLD;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ? State.READY : State.DENIED;
        } catch (Throwable t) {
            return State.STOPPED;
        }
    }

    /** Shizuku's own "allow Buddy?" dialog (when it's running). */
    static void requestPermission() {
        try {
            Shizuku.requestPermission(43);
        } catch (Throwable ignored) {
        }
    }

    private static Shizuku.UserServiceArgs args(Context c) {
        return new Shizuku.UserServiceArgs(new ComponentName(c.getPackageName(), ShellService.class.getName()))
                .daemon(false).processNameSuffix("shell").debuggable(false).version(BuildInfo.VERSION_CODE);
    }

    /** The user service's binder, starting it if needed (waits up to 15 s; not on the main thread). */
    private static IBinder connect(Context c) throws InterruptedException {
        CountDownLatch wait;
        synchronized (ShizukuShell.class) {
            if (service != null && service.pingBinder()) return service;
            service = null;
            connecting = new CountDownLatch(1);
            wait = connecting;
        }
        Context app = c.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Shizuku.bindUserService(args(app), CONN);
            } catch (Throwable t) {
                wait.countDown();
            }
        });
        wait.await(15, TimeUnit.SECONDS);
        synchronized (ShizukuShell.class) {
            return service;
        }
    }

    /** Runs the command with the shell's rights: {exit code, output}, or throws with what's missing. */
    static Object[] run(Context c, String cmd, int timeoutSec) throws Exception {
        State s = state(c);
        if (s != State.READY) throw new IllegalStateException(explain(s));
        IBinder b = connect(c);
        if (b == null) throw new IllegalStateException("Shizuku didn't start Buddy's shell service. Try again, or restart Shizuku.");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeString(cmd);
            data.writeInt(timeoutSec);
            b.transact(ShellService.RUN, data, reply, 0);
            return new Object[]{reply.readInt(), reply.readString()};
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** For the agent: what's missing, and what the user does about it. */
    static String explain(State s) {
        switch (s) {
            case MISSING:
                return "Shizuku isn't installed, so shell_privileged doesn't work. If the user wants it: install Shizuku, "
                        + "start it (wireless debugging or root), then allow Buddy in Check Buddy.";
            case STOPPED:
                return "Shizuku is installed but not running. The user starts it in the Shizuku app (after a restart it "
                        + "has to be started again), then tries again.";
            case OLD:
                return "This Shizuku is too old. The user updates it.";
            case DENIED:
                return "Shizuku hasn't allowed Buddy yet. The user allows it in Buddy: Settings, Check Buddy, Shizuku (Fix).";
            default:
                return "Shizuku is ready.";
        }
    }
}
