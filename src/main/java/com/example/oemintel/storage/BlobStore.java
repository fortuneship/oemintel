package com.example.oemintel.storage;

/**
 * Content-addressed storage for raw bodies and files (AD-18): the key derives from the SHA-256 of the content, so
 * identical content is stored once and stored objects never change.
 */
public interface BlobStore {

	/** @return the stored blob's key and hash; storing the same bytes twice returns the same key */
	StoredBlob put(String namespace, byte[] content);

	byte[] get(String key);

	record StoredBlob(String key, String sha256, long size) {
	}

}
