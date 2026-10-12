package ru.doksi.hungergames.util;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Запись файлов через временный файл и атомарную замену.
 * Сбой сервера во время записи не оставляет на диске обрезанный файл.
 */
public final class SafeFiles {
    private SafeFiles() { }

    /** Обычное сохранение настроек: без ожидания физической записи на диск. */
    public static void saveYaml(YamlConfiguration config, File file) throws IOException {
        write(file.toPath(), config.saveToString().getBytes(StandardCharsets.UTF_8), false);
    }

    /** Сохранение с ожиданием записи на диск. Для данных, после которых сразу меняется игрок. */
    public static void saveYamlDurably(YamlConfiguration config, File file) throws IOException {
        write(file.toPath(), config.saveToString().getBytes(StandardCharsets.UTF_8), true);
    }

    public static void write(Path target, byte[] bytes, boolean sync) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            if (sync) channel.force(true);
        }
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
