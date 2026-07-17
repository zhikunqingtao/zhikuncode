package com.aicodeassistant.interaction;

/** In-process notification only; the database row remains the authority. */
public record InteractionTerminalEvent(InteractionRequest request) {}
