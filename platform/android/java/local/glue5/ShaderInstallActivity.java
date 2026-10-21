package local.glue5;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Button;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs bundled shaders using the app's own storage access before SDL starts. */
public class ShaderInstallActivity extends Activity {
    private static final String SHADER_VERSION =
            "9df9a83a283b55b8f61d4b37ddce3a2929fa7c90af9a8d6e46544699142c146c";
    private static final int FILE_COUNT = 6615;
    private TextView status;
    private ProgressBar progress;
    private LinearLayout layout;
    private boolean busy = true;
    private static final int PICK_DATA = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(48, 48, 48, 48);
        status = new TextView(this);
        status.setText("Подготовка шейдеров… Не закрывайте приложение.");
        status.setTextSize(20);
        layout.addView(status);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(FILE_COUNT);
        layout.addView(progress, new LinearLayout.LayoutParams(-1, -2));
        setContentView(layout);
        new Thread(() -> {
            try {
                installShaders();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    showMenu("Шейдеры готовы. Можно импортировать ZIP с папкой data или запустить игру.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    new AlertDialog.Builder(this)
                            .setTitle("Не удалось установить шейдеры")
                            .setMessage("Проверьте свободное место и откройте приложение снова. "
                                    + "Игровые данные сохранены.\n\n" + e.getMessage())
                            .setPositiveButton("Закрыть", (dialog, which) -> finish())
                            .setCancelable(false).show();
                });
            }
        }, "shader-installer").start();
    }

    private void showMenu(String message) {
        busy = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        layout.removeAllViews();
        status.setText(message);
        layout.addView(status);
        Button importButton = new Button(this);
        importButton.setText("Импортировать data из ZIP");
        importButton.setOnClickListener(view -> {
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            picker.addCategory(Intent.CATEGORY_OPENABLE);
            picker.setType("*/*");
            picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(picker, PICK_DATA);
        });
        layout.addView(importButton);
        Button play = new Button(this);
        play.setText("Запустить Glue5");
        play.setOnClickListener(view -> {
            Intent game = new Intent(this, Glue5Activity.class);
            if (getIntent().getExtras() != null) game.putExtras(getIntent().getExtras());
            startActivity(game);
            finish();
        });
        layout.addView(play);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent result) {
        super.onActivityResult(requestCode, resultCode, result);
        if (requestCode != PICK_DATA || resultCode != RESULT_OK || result == null || busy) return;
        Uri uri = result.getData();
        if (uri == null) return;
        busy = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        layout.removeAllViews();
        status.setText("Импорт ZIP… Оставьте приложение открытым до завершения.");
        layout.addView(status);
        progress.setIndeterminate(true);
        layout.addView(progress, new LinearLayout.LayoutParams(-1, -2));
        new Thread(() -> {
            try {
                File external = getExternalFilesDir(null);
                if (external == null) throw new IOException("Хранилище недоступно");
                int count;
                try (java.io.InputStream input = getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new IOException("Не удалось открыть ZIP");
                    count = DataArchive.extract(input, new File(external, "game"),
                            (files, bytes, name) -> runOnUiThread(() -> status.setText(
                                    "Импорт: " + files + " файлов, " + (bytes / 1048576) + " МБ\n"
                                    + name + "\nНе закрывайте приложение.")));
                }
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) showMenu(
                            "Импорт завершён: " + count + " файлов. CRC файлов ZIP проверены.\n"
                            + "Архив в Download теперь можно удалить.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) showMenu(
                            "Импорт не завершён: " + e.getMessage() + "\n"
                            + "Проверьте архив и свободное место, затем выберите ZIP повторно. "
                            + "Уже распакованные файлы сохранены; повторный импорт заменит их.");
                });
            }
        }, "data-importer").start();
    }

    @Override
    public void onBackPressed() {
        if (!busy) super.onBackPressed();
    }

    private void installShaders() throws IOException {
        File external = getExternalFilesDir(null);
        if (external == null) throw new IOException("Хранилище телефона недоступно");
        File shaders = new File(external, "game/b/8b0b5899ed/shaders");
        File marker = new File(shaders, ".installed-" + SHADER_VERSION);
        if (marker.isFile() && new File(shaders, "index.json").isFile()) return;
        if (!shaders.isDirectory() && !shaders.mkdirs()) {
            throw new IOException("Не удалось создать " + shaders);
        }
        String root = shaders.getCanonicalPath() + File.separator;
        int count = 0;
        byte[] buffer = new byte[65536];
        try (ZipInputStream zip = new ZipInputStream(getAssets().open("shaders.zip"))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File output = new File(shaders, entry.getName());
                if (!output.getCanonicalPath().startsWith(root)) {
                    throw new IOException("Недопустимый путь в архиве шейдеров");
                }
                if (entry.isDirectory()) {
                    if (!output.isDirectory() && !output.mkdirs()) {
                        throw new IOException("Не удалось создать " + output);
                    }
                    continue;
                }
                File parent = output.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Не удалось создать " + parent);
                }
                File temporary = File.createTempFile("shader-", ".part", getCacheDir());
                try {
                    try (FileOutputStream stream = new FileOutputStream(temporary)) {
                        int length;
                        while ((length = zip.read(buffer)) != -1) {
                            stream.write(buffer, 0, length);
                        }
                    }
                    // Cache and external storage may be on different filesystems.
                    try (java.io.FileInputStream input = new java.io.FileInputStream(temporary);
                         FileOutputStream stream = new FileOutputStream(output)) {
                        int length;
                        while ((length = input.read(buffer)) != -1) stream.write(buffer, 0, length);
                    }
                } finally {
                    temporary.delete();
                }
                count++;
                if (count % 50 == 0 || count == FILE_COUNT) {
                    final int done = count;
                    runOnUiThread(() -> {
                        progress.setProgress(done);
                        status.setText("Установка шейдеров: " + done + " / " + FILE_COUNT);
                    });
                }
            }
        }
        if (count != FILE_COUNT || !new File(shaders, "index.json").isFile()) {
            throw new IOException("Архив шейдеров неполон");
        }
        if (!marker.createNewFile() && !marker.isFile()) {
            throw new IOException("Не удалось сохранить отметку установки");
        }
    }
}
