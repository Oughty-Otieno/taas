package com.taas.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.taas.common.BusinessRuleException;
import com.taas.common.IdempotencyConflictException;
import com.taas.common.LedgerIntegrityException;
import com.taas.common.NotFoundException;

@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(BusinessRuleException.class)
    ProblemDetail rule(BusinessRuleException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Rejected by workflow rules", e.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail conflict(IdempotencyConflictException e) {
        return problem(HttpStatus.CONFLICT, "Transaction id reused", e.getMessage());
    }

    @ExceptionHandler(LedgerIntegrityException.class)
    ProblemDetail integrity(LedgerIntegrityException e) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Ledger integrity failure", e.getMessage());
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
