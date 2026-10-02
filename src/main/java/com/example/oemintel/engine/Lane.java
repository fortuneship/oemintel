package com.example.oemintel.engine;

/**
 * Execution lanes with separate concurrency (AD-05): a slow or paused host must not starve
 * CPU-bound parsing, and LLM calls are limited independently of both.
 */
public enum Lane {

	NETWORK,
	LOCAL,
	LLM

}
