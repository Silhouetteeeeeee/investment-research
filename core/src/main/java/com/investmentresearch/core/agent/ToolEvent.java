package com.investmentresearch.core.agent;

/** One completed tool call, as shown in the research timeline. */
public record ToolEvent(String name, String company, boolean ok, boolean cached, long durationMs) {}
