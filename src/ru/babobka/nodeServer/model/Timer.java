package ru.babobka.nodeServer.model;

public class Timer {

	private final long startTime;

	public Timer() {
		this.startTime = System.currentTimeMillis();
	}

	public long getTimePassed() {
		return System.currentTimeMillis() - startTime;
	}
}
