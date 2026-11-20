package com.taas.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.taas.common.InvalidRequest;
import com.taas.common.LedgerIntegrityException;
import com.taas.common.NotFoundException;
import com.taas.workflow.CommandRunner;

@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    @ExceptionHandler(Http.AccessDenied.class)
    ProblemDetail denied(Http.AccessDenied e) {
        ProblemDetail p = problem(HttpStatus.FORBIDDEN, "Forbidden", e.getMessage());
        p.setProperty("code", "ACCOUNT_NOT_PERMITTED");
        return p;
    }

    @ExceptionHandler(CommandRunner.IdempotencyConflict.class)
    ProblemDetail conflict(CommandRunner.IdempotencyConflict e) {
        ProblemDetail p = problem(HttpStatus.CONFLICT, "Idempotency key reused", e.getMessage());
        p.setProperty("code", "IDEMPOTENCY_KEY_REUSED");
        return p;
    }

    @ExceptionHandler(InvalidRequest.class)
    ProblemDetail invalid(InvalidRequest e) {
        ProblemDetail p = problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage());
        if (!e.errors().isEmpty()) {
            p.setProperty("errors", e.errors());
        }
        return p;
    }

    @ExceptionHandler(LedgerIntegrityException.class)
    ProblemDetail integrity(LedgerIntegrityException e) {
        return problem(HttpStatus.CONFLICT, "Ledger integrity failure", e.getMessage());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail missingHeader(MissingRequestHeaderException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", "Header " + e.getHeaderName() + " is required");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .sorted()
                .reduce((a, b) -> a + "; " + b)
                .orElse("Invalid request");
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", detail);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
