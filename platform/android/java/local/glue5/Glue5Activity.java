package local.glue5;

import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;

import org.libsdl.app.SDLActivity;

/** The app's activity: SDL's, with our library (libglue5.so) as the main one. */
public class Glue5Activity extends SDLActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
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
    }

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL3", "glue5" };
    }
}
