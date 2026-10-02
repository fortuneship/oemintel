package com.example.oemintel.job;

public enum JobMode {

	/** Discover candidates only; nothing is fetched beyond sitemaps and category pages. */
	DRY_RUN(true, true),
	FULL(true, true),
	/** Candidates are supplied by the user instead of discovered. */
	SEED_LIST(true, false),
	REFRESH(true, true),
	/** Re-run a stage from stored raw data: zero network requests (NFR-04). */
	REEXTRACT(false, false),
	REPARSE_DOCUMENTS(false, false),
	RENORMALIZE(false, false);

	private final boolean networkAccess;

	private final boolean discovers;

	JobMode(boolean networkAccess, boolean discovers) {
		this.networkAccess = networkAccess;
		this.discovers = discovers;
	}

	public boolean networkAccess() {
		return this.networkAccess;
	}

	public boolean discovers() {
		return this.discovers;
	}

	/** Modes that go through candidate admission, as opposed to reprocessing stored data. */
	public boolean admitsCandidates() {
		return this == FULL || this == SEED_LIST || this == REFRESH;
	}

}
