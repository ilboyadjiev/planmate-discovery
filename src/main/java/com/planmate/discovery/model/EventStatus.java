package com.planmate.discovery.model;

/**
 * ACTIVE: the source still lists it. CANCELLED: the source said so, or stopped listing it before it happened.
 * EXPIRED: it is over, so it no longer matters whether the source lists it (nothing is published).
 */
public enum EventStatus {
	ACTIVE, CANCELLED, EXPIRED
}
