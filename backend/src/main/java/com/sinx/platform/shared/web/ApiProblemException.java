package com.sinx.platform.shared.web;

import org.springframework.http.HttpStatus;

public class ApiProblemException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiProblemException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
