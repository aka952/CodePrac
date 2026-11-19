package dev.codecomp.repo;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class RepoErrors {

    @ExceptionHandler(ReviewGrader.UnavailableException.class)
    ProblemDetail aiUnavailable(ReviewGrader.UnavailableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    /** run-service's answer, passed on as it was: the same status, message and Retry-After. */
    @ExceptionHandler(RunClient.RunServiceException.class)
    ResponseEntity<ProblemDetail> runService(RunClient.RunServiceException e) {
        var status = HttpStatus.valueOf(e.status());
        var response = ResponseEntity.status(status);
        if (e.retryAfter() != null) response.header(HttpHeaders.RETRY_AFTER, e.retryAfter());
        return response.body(ProblemDetail.forStatusAndDetail(status, e.getMessage()));
    }
}
