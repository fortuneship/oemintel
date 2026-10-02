package com.example.oemintel.engine;

import java.time.Duration;

/** Periodic housekeeping run by the engine while it is started (lease reaping, job state evaluation). */
public interface EngineTicker {

	Duration interval();

	void tick();

}
