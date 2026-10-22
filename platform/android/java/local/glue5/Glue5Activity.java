package local.glue5;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.view.WindowManager;

import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLSurface;

/** The app's activity: SDL's, with our library (libglue5.so) as the main one. */
public class Glue5Activity extends SDLActivity {
    private Process diagnosticLog;

    private void startDiagnosticLog() {
        java.io.File external = getExternalFilesDir(null);
        if (external == null) return;
        java.io.File directory = new java.io.File(external, "diagnostics");
        if (!directory.isDirectory() && !directory.mkdirs()) return;
        try {
            // Only this app's PID; two rotating 8 MiB files, visible over MTP.
            diagnosticLog = new ProcessBuilder("logcat", "--pid=" + android.os.Process.myPid(),
                    "-v", "threadtime", "-T", "1", "-f",
                    new java.io.File(directory, "renderer.log").getAbsolutePath(),
                    "-r", "8192", "-n", "1").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(
                            new java.io.File(directory, "logcat-status.txt"))).start();
            android.util.Log.i("Glue5", "Diagnostic launch: 0.1.4-next-vulkan; "
                    + Build.MANUFACTURER + " " + Build.MODEL + "; " + Build.FINGERPRINT);
        } catch (java.io.IOException e) {
            android.util.Log.e("Glue5", "Cannot start diagnostic log", e);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        startDiagnosticLog();
        // The same switches as a desktop run, RUST_LOG and GLUE5_*: string
        // extras of the launch intent (`mise run android:run` passes them),
        // set in the environment before SDL starts the library's main.
        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            for (String key : extras.keySet()) {
                String value = extras.getString(key);
                if (value != null && (key.equals("RUST_LOG") || key.startsWith("GLUE5_"))) {
                    try {
                        Os.setenv(key, value, true);
                    } catch (ErrnoException e) {
                        // Left unset.
                    }
                }
            }
        }
        // Apply before SDL starts the native renderer. Absence, not "0",
        // enables ETC2 again: the renderer checks whether the variable exists.
        boolean noEtc2 = getSharedPreferences("graphics-workarounds", MODE_PRIVATE)
                .getBoolean("no-etc2", true);
        try {
            if (noEtc2) Os.setenv("GLUE5_NO_ETC2", "1", true);
            else Os.unsetenv("GLUE5_NO_ETC2");
            android.util.Log.i("Glue5", "Texture workaround: GLUE5_NO_ETC2=" + noEtc2);
        } catch (ErrnoException e) {
            android.util.Log.e("Glue5", "Cannot apply ETC2 setting", e);
        }
        super.onCreate(savedInstanceState);
        // Android 11 and later tell where the on-screen keyboard is
        // (Glue5Surface): the app's pages end above it, so the window stays
        // put. Android 10 pans the window for the text being typed.
        if (Build.VERSION.SDK_INT >= 30 /* Android 11 (R) */) {
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (diagnosticLog != null) diagnosticLog.destroy();
    }

    @Override
    protected SDLSurface createSDLSurface(Context context) {
        return new Glue5Surface(context);
    }

    /**
     * From keyboard.rs: 1, the window pans for the text being typed (the
     * game's text box, which the game draws where it does); 0, it stays put
     * (the app's pages end above the keyboard). Sent before the keyboard
     * shows, through the queue SDL shows it with.
     */
    private static final int COMMAND_KEYBOARD_PANS = COMMAND_USER;

    @Override
    protected boolean onUnhandledMessage(int command, Object param) {
        if (command == COMMAND_KEYBOARD_PANS) {
            if (Build.VERSION.SDK_INT >= 30 /* Android 11 (R) */) {
                boolean pans = param instanceof Integer && (Integer) param != 0;
                getWindow().setSoftInputMode(pans
                        ? WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
                        : WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
            }
            return true;
        }
        return super.onUnhandledMessage(command, param);
    }

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL3", "glue5" };
    }
}
