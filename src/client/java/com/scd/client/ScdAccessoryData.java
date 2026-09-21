package com.scd.client;

/**
 * Holds the latest accessory-bag fetch result/state - shared between ScdClient (which owns the one
 * instance and triggers refreshes via ScdApiClient) and ScdAccessoryScreen (which polls it every
 * frame and rebuilds its own widgets on a state change, see that class for why it's polling rather
 * than being pushed to directly).
 */
public class ScdAccessoryData {
	public enum Status {
		IDLE, LOADING, LOADED, ERROR
	}

	private volatile Status status = Status.IDLE;
	private volatile ScdApiClient.AccessorySummary summary;
	private volatile String errorMessage;

	public Status status() {
		return status;
	}

	public ScdApiClient.AccessorySummary summary() {
		return summary;
	}

	public String errorMessage() {
		return errorMessage;
	}

	public void markLoading() {
		status = Status.LOADING;
	}

	public void markLoaded(ScdApiClient.AccessorySummary summary) {
		this.summary = summary;
		this.status = Status.LOADED;
	}

	public void markError(String message) {
		this.errorMessage = message;
		this.status = Status.ERROR;
	}
}
