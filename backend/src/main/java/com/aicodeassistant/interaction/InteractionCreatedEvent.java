package com.aicodeassistant.interaction;

/** Published after the interaction/Run transaction commits. */
public record InteractionCreatedEvent(InteractionRequest request) { }
