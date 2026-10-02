package com.example.oemintel.engine;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * The handlers deployed in this build. Dispatchers only lease task types that have a handler, so work for a
 * stage that doesn't exist yet waits in the queue instead of failing.
 */
@Component
public class HandlerRegistry {

	private final Map<TaskType, TaskHandler> handlers = new EnumMap<>(TaskType.class);

	public HandlerRegistry(List<TaskHandler> handlers) {
		for (TaskHandler handler : handlers) {
			TaskHandler existing = this.handlers.put(handler.taskType(), handler);
			if (existing != null) {
				throw new IllegalStateException("Two handlers for %s: %s and %s".formatted(handler.taskType(),
						existing.getClass().getName(), handler.getClass().getName()));
			}
		}
	}

	public Optional<TaskHandler> handlerFor(TaskType type) {
		return Optional.ofNullable(this.handlers.get(type));
	}

	public Set<TaskType> typesFor(Lane lane) {
		return this.handlers.keySet().stream().filter((type) -> type.lane() == lane).collect(Collectors.toSet());
	}

}
