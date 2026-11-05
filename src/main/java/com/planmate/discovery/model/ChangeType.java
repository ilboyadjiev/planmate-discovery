package com.planmate.discovery.model;

/** How a fetched event compares with what discovery knew before. */
public enum ChangeType {
	NEW, CHANGED, UNCHANGED, CANCELLED
}
