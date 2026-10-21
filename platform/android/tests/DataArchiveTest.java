import local.glue5.DataArchive;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Standalone JVM regression checks for the storage importer, without an Android device. */
public class DataArchiveTest {
    static byte[] archive(String name, byte[] content, boolean stored) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            ZipEntry entry = new ZipEntry(name);
            if (stored) {
                CRC32 crc = new CRC32();
                crc.update(content);
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(content.length);
                entry.setCrc(crc.getValue());
            }
            zip.putNextEntry(entry);
            zip.write(content);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    static int extract(byte[] bytes, Path game) throws IOException {
        return DataArchive.extract(new ByteArrayInputStream(bytes), game.toFile(), (n, b, p) -> {});
    }

    static void rejects(byte[] bytes, Path game) throws IOException {
        try {
            extract(bytes, game);
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Invalid archive accepted");
    }

    public static void main(String[] args) throws Exception {
        Path game = Files.createTempDirectory("glue5-import-test-");
        try {
            byte[] good = "original".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String name = "data/x64/models/plantsmgr.ydd";
            if (extract(archive(name, good, false), game) != 1) throw new AssertionError();
            byte[] replacement = "replacement".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            extract(archive(name, replacement, true), game);
            if (!Arrays.equals(Files.readAllBytes(game.resolve(name)), replacement)) throw new AssertionError();

            byte[] corrupt = archive(name, good, true);
            int nameSize = (corrupt[26] & 255) | ((corrupt[27] & 255) << 8);
            int extraSize = (corrupt[28] & 255) | ((corrupt[29] & 255) << 8);
            corrupt[30 + nameSize + extraSize] ^= 1;
            rejects(corrupt, game);
            if (!Arrays.equals(Files.readAllBytes(game.resolve(name)), replacement)) {
                throw new AssertionError("CRC failure replaced existing data");
            }
            if (Files.exists(game.resolve("data/x64/models/.glue5-import.part"))) throw new AssertionError();
            rejects(archive("data/../escape", good, false), game);
            rejects(archive("playgta5.com/data/file", good, false), game);
            rejects(new byte[0], game);
            if (args.length > 0) {
                try (java.io.InputStream zip64 = Files.newInputStream(Path.of(args[0]))) {
                    if (DataArchive.extract(zip64, game.toFile(), (n, b, p) -> {}) != 1) throw new AssertionError();
                }
            }
            System.out.println("PASS: stored/deflated ZIP, replacement, CRC failure, traversal, wrong root, empty input, ZIP64 fixture");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(game)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
