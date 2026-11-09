package app.buddy.assistant;

import android.os.Binder;
import android.os.Parcel;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * Buddy Full's Shizuku user service: Shizuku starts this class in a process of its own with the shell's rights (what
 * `adb shell` may do) and hands its binder to Buddy (ShizukuShell). It runs one command per call with /system/bin/sh
 * and answers with the exit code and the output. Nothing else of Buddy runs in that process.
 */
public class ShellService extends Binder {
    /** run(command, timeoutSeconds) -> (exitCode, output) */
    static final int RUN = FIRST_CALL_TRANSACTION;
    /** Shizuku's own "destroy" (its AIDL method id 16777114): the service is being stopped. */
    static final int DESTROY = 16777115;
    /** Output past this is cut (a binder reply has to stay well under 1 MB). */
    static final int MAX_OUTPUT = 256 * 1024;
    /** The exit code when the command ran out of time and was stopped. */
    static final int TIMED_OUT = -2;

    public ShellService() {
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
        if (code == DESTROY) {
            System.exit(0);
            return true;
        }
        if (code != RUN) return super.onTransact(code, data, reply, flags);
        String cmd = data.readString();
        int timeout = data.readInt();
        int exit;
        String out;
        try {
            Process p = new ProcessBuilder("/system/bin/sh", "-c", cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                byte[] b = new byte[8192];
                try (InputStream in = p.getInputStream()) {
                    int n;
                    while ((n = in.read(b)) > 0) {
                        synchronized (buf) {
                            if (buf.size() < MAX_OUTPUT) buf.write(b, 0, Math.min(n, MAX_OUTPUT - buf.size()));
                        }
                    }
                } catch (Exception ignored) {
                }
            });
            reader.start();
            if (p.waitFor(Math.max(1, timeout), TimeUnit.SECONDS)) {
                exit = p.exitValue();
            } else {
                p.destroyForcibly();
                exit = TIMED_OUT;
            }
            reader.join(2000);
            synchronized (buf) {
                out = buf.toString("UTF-8");
                if (buf.size() >= MAX_OUTPUT) out += "\n[output cut at " + (MAX_OUTPUT / 1024) + " KB]";
            }
        } catch (Exception e) {
            exit = -1;
            out = "Couldn't run it: " + e;
        }
        if (reply != null) {
            reply.writeInt(exit);
            reply.writeString(out);
        }
        return true;
    }
}
