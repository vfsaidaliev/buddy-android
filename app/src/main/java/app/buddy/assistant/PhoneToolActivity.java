package app.buddy.assistant;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.MediaStore;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A see-through screen for the phone tools that need Buddy in front: reading the clipboard (Android allows only the
 * app in front), taking a photo with the camera, and asking for the location permission. It does its one thing,
 * hands the result to the waiting tool and closes.
 */
public class PhoneToolActivity extends Activity {
    static final String CLIPBOARD = "clipboard", PHOTO = "photo", LOCATION_PERMISSION = "location",
            CALENDAR_PERMISSION = "calendar",
            /** Buddy Full: Android's permission dialog for these (arg: names, comma-separated); true if all granted. */
            PERMISSIONS = "permissions",
            /** Buddy Full: a photo without the user pressing the shutter (arg: "back" or "front"); true, or an error. */
            SNAPSHOT = "snapshot";
    private static final int REQ = 7;

    private static CompletableFuture<Object> pending;
    private boolean done;

    /** Opens the screen for this job and waits for its result (null when it didn't come in time). One at a time. */
    static synchronized Object run(Context c, String job, String arg, int timeoutSeconds) throws InterruptedException {
        CompletableFuture<Object> f = new CompletableFuture<>();
        pending = f;
        c.startActivity(new Intent(c, PhoneToolActivity.class).putExtra("job", job).putExtra("arg", arg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        try {
            return f.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            return null;
        } finally {
            pending = null;
        }
    }

    private void finish(Object result) {
        if (done) return;
        done = true;
        CompletableFuture<Object> f = pending;
        if (f != null) f.complete(result);
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String job = getIntent().getStringExtra("job");
        if (state != null) return; // recreated: the first one already started its job
        if (PHOTO.equals(job)) {
            FilesProvider.captureFile(this).delete();
            Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, FilesProvider.captureUri())
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            cam.setClipData(ClipData.newRawUri("photo", FilesProvider.captureUri()));
            try {
                startActivityForResult(cam, REQ);
            } catch (Exception e) {
                finish(false);
            }
        } else if (LOCATION_PERMISSION.equals(job)) {
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQ);
        } else if (CALENDAR_PERMISSION.equals(job)) {
            requestPermissions(new String[]{android.Manifest.permission.READ_CALENDAR}, REQ);
        } else if (PERMISSIONS.equals(job)) {
            String arg = getIntent().getStringExtra("arg");
            requestPermissions(arg == null ? new String[0] : arg.split(","), REQ);
        } else if (SNAPSHOT.equals(job)) {
            if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) snapshot();
            else requestPermissions(new String[]{android.Manifest.permission.CAMERA}, REQ);
        } else if (!CLIPBOARD.equals(job)) {
            finish(null);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (!focus || !CLIPBOARD.equals(getIntent().getStringExtra("job"))) return;
        String text = "";
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence t = clip.getItemAt(0).coerceToText(this);
                if (t != null) text = t.toString();
            }
        } catch (Exception ignored) {
        }
        finish(text);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        finish(result == RESULT_OK);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        boolean any = false, all = results.length > 0;
        for (int r : results) {
            any |= r == PackageManager.PERMISSION_GRANTED;
            all &= r == PackageManager.PERMISSION_GRANTED;
        }
        String job = getIntent().getStringExtra("job");
        if (SNAPSHOT.equals(job)) {
            if (all) snapshot();
            else finish("The user didn't allow Buddy to use the camera.");
            return;
        }
        finish(PERMISSIONS.equals(job) ? all : any);
    }

    // ----------------------------------------------------------- snapshot

    private android.os.HandlerThread camThread;
    private android.hardware.camera2.CameraDevice camera;
    private android.media.ImageReader reader;
    private android.graphics.SurfaceTexture preview;

    /**
     * Opens the chosen camera, lets exposure and focus settle on a few preview frames (to nowhere), takes one JPEG
     * into FilesProvider.snapshotFile and finishes with true (or with what went wrong). Android allows the camera only
     * to the app in front: this see-through screen is it.
     */
    private void snapshot() {
        camThread = new android.os.HandlerThread("snapshot");
        camThread.start();
        android.os.Handler h = new android.os.Handler(camThread.getLooper());
        h.post(() -> {
            try {
                openCamera(h);
            } catch (Exception e) {
                failSnapshot("Couldn't use the camera (" + e.getMessage() + ").");
            }
        });
    }

    private void openCamera(android.os.Handler h) throws Exception {
        android.hardware.camera2.CameraManager cm = getSystemService(android.hardware.camera2.CameraManager.class);
        boolean front = "front".equals(getIntent().getStringExtra("arg"));
        int want = front ? android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
                : android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK;
        String id = null;
        android.hardware.camera2.CameraCharacteristics ch = null;
        for (String c : cm.getCameraIdList()) {
            android.hardware.camera2.CameraCharacteristics cc = cm.getCameraCharacteristics(c);
            Integer facing = cc.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == want) {
                id = c;
                ch = cc;
                break;
            }
        }
        if (id == null) {
            failSnapshot("This phone has no " + (front ? "front" : "back") + " camera.");
            return;
        }
        android.hardware.camera2.params.StreamConfigurationMap map =
                ch.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        android.util.Size big = largest(map.getOutputSizes(android.graphics.ImageFormat.JPEG), Integer.MAX_VALUE);
        android.util.Size small = largest(map.getOutputSizes(android.graphics.SurfaceTexture.class), 1920 * 1080);
        Integer sensor = ch.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION);
        final int orientation = sensor == null ? 0 : sensor; // the phone held upright
        reader = android.media.ImageReader.newInstance(big.getWidth(), big.getHeight(), android.graphics.ImageFormat.JPEG, 2);
        reader.setOnImageAvailableListener(r -> {
            try {
                byte[] bytes;
                try (android.media.Image img = r.acquireLatestImage()) { // closed before the reader is
                    if (img == null) return;
                    java.nio.ByteBuffer buf = img.getPlanes()[0].getBuffer();
                    bytes = new byte[buf.remaining()];
                    buf.get(bytes);
                }
                try (java.io.FileOutputStream o = new java.io.FileOutputStream(FilesProvider.snapshotFile(this))) {
                    o.write(bytes);
                }
                closeCamera();
                runOnUiThread(() -> finish(true));
            } catch (Exception e) {
                failSnapshot("Couldn't save the photo (" + e.getMessage() + ").");
            }
        }, h);
        preview = new android.graphics.SurfaceTexture(false);
        preview.setDefaultBufferSize(small.getWidth(), small.getHeight());
        final android.view.Surface previewSurface = new android.view.Surface(preview);
        cm.openCamera(id, new android.hardware.camera2.CameraDevice.StateCallback() {
            @Override
            public void onOpened(android.hardware.camera2.CameraDevice d) {
                camera = d;
                try {
                    d.createCaptureSession(java.util.Arrays.asList(previewSurface, reader.getSurface()),
                            new android.hardware.camera2.CameraCaptureSession.StateCallback() {
                                @Override
                                public void onConfigured(android.hardware.camera2.CameraCaptureSession s) {
                                    try {
                                        android.hardware.camera2.CaptureRequest.Builder p =
                                                d.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW);
                                        p.addTarget(previewSurface);
                                        s.setRepeatingRequest(p.build(), null, h);
                                        // exposure and focus settle on the preview first, then one still
                                        h.postDelayed(() -> {
                                            try {
                                                android.hardware.camera2.CaptureRequest.Builder still =
                                                        d.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_STILL_CAPTURE);
                                                still.addTarget(reader.getSurface());
                                                still.set(android.hardware.camera2.CaptureRequest.JPEG_ORIENTATION, orientation);
                                                still.set(android.hardware.camera2.CaptureRequest.JPEG_QUALITY, (byte) 92);
                                                s.capture(still.build(), null, h);
                                            } catch (Exception e) {
                                                failSnapshot("The camera didn't take the photo (" + e.getMessage() + ").");
                                            }
                                        }, 1200);
                                    } catch (Exception e) {
                                        failSnapshot("The camera didn't start (" + e.getMessage() + ").");
                                    }
                                }

                                @Override
                                public void onConfigureFailed(android.hardware.camera2.CameraCaptureSession s) {
                                    failSnapshot("The camera couldn't be set up.");
                                }
                            }, h);
                } catch (Exception e) {
                    failSnapshot("The camera couldn't be set up (" + e.getMessage() + ").");
                }
            }

            @Override
            public void onDisconnected(android.hardware.camera2.CameraDevice d) {
                camera = d;
                failSnapshot("Another app took the camera.");
            }

            @Override
            public void onError(android.hardware.camera2.CameraDevice d, int error) {
                camera = d;
                failSnapshot("The camera is busy or unavailable (error " + error + ").");
            }
        }, h);
    }

    /** The biggest size of these with at most maxPixels pixels (the smallest if none fits). */
    private static android.util.Size largest(android.util.Size[] sizes, long maxPixels) {
        android.util.Size best = null, smallest = null;
        for (android.util.Size z : sizes) {
            long px = (long) z.getWidth() * z.getHeight();
            if (smallest == null || px < (long) smallest.getWidth() * smallest.getHeight()) smallest = z;
            if (px <= maxPixels && (best == null || px > (long) best.getWidth() * best.getHeight())) best = z;
        }
        return best != null ? best : smallest;
    }

    private void failSnapshot(String why) {
        closeCamera();
        runOnUiThread(() -> finish(why));
    }

    private synchronized void closeCamera() {
        if (camera != null) {
            camera.close();
            camera = null;
        }
        if (reader != null) {
            reader.close();
            reader = null;
        }
        if (preview != null) {
            preview.release();
            preview = null;
        }
        if (camThread != null) {
            camThread.quitSafely();
            camThread = null;
        }
    }

    @Override
    protected void onDestroy() {
        closeCamera();
        super.onDestroy();
        if (!done && isFinishing()) {
            CompletableFuture<Object> f = pending;
            if (f != null) f.complete(null);
        }
    }
}
