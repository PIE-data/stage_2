package es.ulpgc.piedata.ingestion.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

public final class BookClaim implements AutoCloseable {

    private static final ConcurrentMap<Long, ReentrantLock> IN_PROCESS_LOCKS = new ConcurrentHashMap<>();

    private final long bookId;
    private final ReentrantLock threadLock;
    private final FileChannel channel;
    private final FileLock fileLock;

    private BookClaim(long bookId, ReentrantLock threadLock, FileChannel channel, FileLock fileLock) {
        this.bookId = bookId;
        this.threadLock = threadLock;
        this.channel = channel;
        this.fileLock = fileLock;
    }

    public static BookClaim acquire(Path dataDir, long bookId) {
        ReentrantLock threadLock = IN_PROCESS_LOCKS.computeIfAbsent(bookId, id -> new ReentrantLock());
        threadLock.lock();
        Path claimsDir = dataDir.resolve("control").resolve("claims");
        try {
            Files.createDirectories(claimsDir);
            Path lockFile = claimsDir.resolve(bookId + ".lock");
            FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock fileLock = channel.lock();
            return new BookClaim(bookId, threadLock, channel, fileLock);
        } catch (IOException e) {
            threadLock.unlock();
            throw new UncheckedIOException("cannot acquire claim for book " + bookId, e);
        } catch (RuntimeException e) {
            threadLock.unlock();
            throw e;
        }
    }

    @Override
    public void close() {
        try {
            if (fileLock != null && fileLock.isValid()) {
                fileLock.release();
            }
        } catch (IOException ignored) {
        } finally {
            try {
                if (channel != null && channel.isOpen()) {
                    channel.close();
                }
            } catch (IOException ignored) {
            } finally {
                threadLock.unlock();
                if (!threadLock.hasQueuedThreads()) {
                    IN_PROCESS_LOCKS.remove(bookId, threadLock);
                }
            }
        }
    }
}