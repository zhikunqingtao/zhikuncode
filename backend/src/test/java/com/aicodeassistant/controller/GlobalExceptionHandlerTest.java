package com.aicodeassistant.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {
    @Test
    void preservesIntentionalResponseStatus() {
        var response = new GlobalExceptionHandler().handleResponseStatus(
                new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "SESSION_FILE_OUTSIDE_WORKSPACE"));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().toString())
                .contains("SESSION_FILE_OUTSIDE_WORKSPACE");
    }
}
