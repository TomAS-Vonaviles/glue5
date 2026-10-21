package local.glue5;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Streaming ZIP/ZIP64 import; each file is replaced only after its CRC passes. */
public final class DataArchive {
    public interface Progress {
        void update(int files, long bytes, String name);
    }

    public static int extract(InputStream input, File game, Progress progress) throws IOException {
        File data = new File(game, "data");
        if (!data.isDirectory() && !data.mkdirs()) throw new IOException("Cannot create " + data);
        String root = data.getCanonicalPath() + File.separator;
        int count = 0;
        long total = 0;
        long lastUpdate = 0;
        byte[] buffer = new byte[256 * 1024];
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(input, buffer.length))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.equals("data/") && entry.isDirectory()) continue;
                if (!name.startsWith("data/") || name.contains("\\")
                        || java.util.Arrays.asList(name.split("/")).contains("..")) {
                    throw new IOException("ZIP должен содержать только папку data. Неверный путь: " + name);
                }
                File destination = new File(game, name);
                if (!destination.getCanonicalPath().startsWith(root)) {
                    throw new IOException("Недопустимый путь: " + name);
                }
                if (entry.isDirectory()) {
                    if (!destination.isDirectory() && !destination.mkdirs()) {
                        throw new IOException("Cannot create " + destination);
                    }
                    continue;
                }
                File parent = destination.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
                File partial = new File(parent, ".glue5-import.part");
                if (destination.equals(partial)) throw new IOException("Reserved filename: " + name);
                try {
                    try (FileOutputStream output = new FileOutputStream(partial)) {
                        int length;
                        while ((length = zip.read(buffer)) != -1) {
                            output.write(buffer, 0, length);
                            total += length;
                            long now = System.nanoTime();
                            if (now - lastUpdate > 250_000_000L) {
                                progress.update(count, total, name);
                                lastUpdate = now;
                            }
                        }
                    }
                    zip.closeEntry();
                    Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    count++;
                } finally {
                    partial.delete();
                }
            }
        }
        if (count == 0) throw new IOException("В ZIP не найдены файлы внутри папки data");
        progress.update(count, total, "Готово");
        return count;
    }

    private DataArchive() {}
}
