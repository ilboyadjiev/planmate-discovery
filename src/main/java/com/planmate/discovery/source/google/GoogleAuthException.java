package com.planmate.discovery.source.google;

/**
 * The Google OAuth token exchange failed — most often because the refresh token expired or was revoked and the
 * ingestion account must be re-authorized. Thrown so a lapsed token is obvious in the logs rather than looking like
 * a transient fetch error.
 */
class GoogleAuthException extends RuntimeException {

	GoogleAuthException(String message, Throwable cause) {
		super(message, cause);
	}
}
