package dev.codecomp.web;

import dev.codecomp.repo.ReviewGrader;
import dev.codecomp.run.RateLimiter.RateLimitedException;
import dev.codecomp.sandbox.Sandbox;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiErrors {

    @ExceptionHandler(Sandbox.UnsupportedLanguageException.class)
    ProblemDetail unsupported(Sandbox.UnsupportedLanguageException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(Sandbox.BusyException.class)
    ProblemDetail busy(Sandbox.BusyException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(ReviewGrader.UnavailableException.class)
    ProblemDetail graderUnavailable(ReviewGrader.UnavailableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(RateLimitedException.class)
    ResponseEntity<ProblemDetail> limited(RateLimitedException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage()));
    }
}
