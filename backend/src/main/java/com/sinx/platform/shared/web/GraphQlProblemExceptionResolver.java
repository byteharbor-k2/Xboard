package com.sinx.platform.shared.web;

import java.util.Map;

import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;

/**
 * Carries business failures out through GraphQL.
 *
 * {@link GlobalExceptionHandler} is a controller advice and does not see
 * GraphQL resolvers, so an {@link ApiProblemException} raised in one was
 * reported as an unresolved internal error - the caller lost both the reason
 * and the code, and every rejected coupon or purchase limit looked like a
 * server fault.
 */
@Component
public class GraphQlProblemExceptionResolver
    extends DataFetcherExceptionResolverAdapter {

    @Override
    protected GraphQLError resolveToSingleError(
        Throwable exception,
        DataFetchingEnvironment environment
    ) {
        if (!(exception instanceof ApiProblemException problem)) {
            return null;
        }
        return GraphqlErrorBuilder.newError(environment)
            .errorType(errorTypeFor(problem.getStatus()))
            .message(problem.getMessage())
            .extensions(Map.of(
                "code", problem.getCode(),
                "status", problem.getStatus().value()
            ))
            .build();
    }

    private ErrorType errorTypeFor(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> ErrorType.NOT_FOUND;
            case UNAUTHORIZED -> ErrorType.UNAUTHORIZED;
            case FORBIDDEN -> ErrorType.FORBIDDEN;
            default -> status.is4xxClientError()
                ? ErrorType.BAD_REQUEST
                : ErrorType.INTERNAL_ERROR;
        };
    }
}
