package com.example.oemintel.engine;

import java.util.Set;

public enum ItemStatus {

	PENDING,
	LEASED,
	RETRY_WAIT,
	DONE,
	DEAD,
	CANCELLED,
	SKIPPED;

	public static final Set<ItemStatus> ACTIVE = Set.of(PENDING, LEASED, RETRY_WAIT);

	public boolean isFinal() {
		return !ACTIVE.contains(this);
	}

}
