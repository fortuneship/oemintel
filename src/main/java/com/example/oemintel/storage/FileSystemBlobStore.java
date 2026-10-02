package com.example.oemintel.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Gzip-compressed blobs on the local disk, keyed by the hash of the uncompressed content. Stands in for MinIO/S3
 * until the object store is chosen (the minio/minio image could not be pulled); the {@link BlobStore} interface is
 * what the rest of the system depends on.
 */
@Component
public class FileSystemBlobStore implements BlobStore {

	private final Path root;

	public FileSystemBlobStore(@Value("${oemintel.storage.root:data/blobs}") Path root) {
		this.root = root.toAbsolutePath();
	}

	@Override
	public StoredBlob put(String namespace, byte[] content) {
		String sha256 = sha256(content);
		String key = "%s/%s/%s/%s.gz".formatted(namespace, sha256.substring(0, 2), sha256.substring(2, 4), sha256);
		Path target = this.root.resolve(key);
		if (Files.exists(target)) {
			return new StoredBlob(key, sha256, content.length);
		}
		try {
			Files.createDirectories(target.getParent());
			// Write to a temp file and move into place, so a crash never leaves a truncated blob under a valid key.
			Path temp = Files.createTempFile(target.getParent(), sha256, ".tmp");
			try {
				Files.write(temp, gzip(content));
				try {
					Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
				}
				catch (AtomicMoveNotSupportedException ex) {
					Files.move(temp, target);
				}
			}
			catch (FileAlreadyExistsException ex) {
				// Another worker stored the same content first; identical bytes, nothing to do.
			}
			finally {
				Files.deleteIfExists(temp);
			}
			return new StoredBlob(key, sha256, content.length);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not store blob " + key, ex);
		}
	}

	@Override
	public byte[] get(String key) {
		try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(this.root.resolve(key)))) {
			return in.readAllBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not read blob " + key, ex);
		}
	}

	public static String sha256(byte[] content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static byte[] gzip(byte[] content) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.max(64, content.length / 4));
		try (GZIPOutputStream out = new GZIPOutputStream(bytes); ByteArrayInputStream in = new ByteArrayInputStream(content)) {
			in.transferTo(out);
		}
		return bytes.toByteArray();
	}

}
