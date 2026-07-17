package com.aicodeassistant.security;

import com.aicodeassistant.run.RunEnvelope;
import com.aicodeassistant.run.RunEnvelopeRepository;
import com.aicodeassistant.session.SessionManager;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Application-object authorization for the single-user runtime.
 *
 * <p>Network authentication is enforced by {@code RemoteAccessSecurityFilter}.
 * This service verifies that client-supplied object identifiers actually exist
 * and belong to the requested session. Transport liveness is deliberately not
 * an authorization signal: reconnect recovery must work while a session is
 * temporarily offline.</p>
 */
@Service
public class SessionAccessAuthorizer {
    private final SessionManager sessions;
    private final RunEnvelopeRepository runs;

    public SessionAccessAuthorizer(SessionManager sessions, RunEnvelopeRepository runs) {
        this.sessions = sessions;
        this.runs = runs;
    }

    public boolean canAccessSession(String requestedSessionId, String assertedSessionId) {
        return requestedSessionId != null && requestedSessionId.equals(assertedSessionId)
                && sessions.loadSession(requestedSessionId).isPresent();
    }

    public Optional<RunEnvelope> accessibleRun(String runId, String assertedSessionId) {
        return runs.findById(runId).filter(run -> canAccessSession(run.sessionId(), assertedSessionId));
    }
}
