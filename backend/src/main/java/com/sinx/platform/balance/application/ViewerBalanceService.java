package com.sinx.platform.balance.application;

import java.util.UUID;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.balance.domain.BalanceLog;
import com.sinx.platform.balance.repository.BalanceLogRepository;
import com.sinx.platform.shared.web.ApiProblemException;

/** Reads only the authenticated account's cash ledger and reconciliation summary. */
@Service
@Transactional(readOnly = true)
public class ViewerBalanceService {

    private final BalanceLogRepository logs;

    public ViewerBalanceService(BalanceLogRepository logs) {
        this.logs = logs;
    }

    public BalanceSummaryView summary(UUID viewerId) {
        BalanceLogRepository.SummaryProjection summary = logs.summary(viewerId);
        if (summary == null) {
            throw new ApiProblemException(
                HttpStatus.NOT_FOUND,
                "USER_NOT_FOUND",
                "The account does not exist"
            );
        }
        return new BalanceSummaryView(
            Long.toString(summary.getBalanceMinor()),
            Long.toString(summary.getOpeningBalanceMinor()),
            Long.toString(summary.getTotalCreditsMinor()),
            Long.toString(summary.getTotalDebitsMinor()),
            summary.getRecordedSince() == null ? null : summary.getRecordedSince().toString()
        );
    }

    public BalanceLogPage logs(UUID viewerId, int requestedPage, int requestedLimit) {
        int pageNumber = Math.max(0, Math.min(requestedPage, 1_000_000));
        int limit = requestedLimit <= 0 ? 20 : Math.min(requestedLimit, 100);
        Page<BalanceLog> found = logs.pageForUser(
            viewerId,
            PageRequest.of(pageNumber, limit)
        );
        List<String> tradeNumbers = found.getContent().stream()
            .map(BalanceLog::getTradeNo)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
        Set<String> ownedTradeNumbers = tradeNumbers.isEmpty()
            ? Set.of()
            : new HashSet<>(logs.findTradeNumbersOwnedByUser(viewerId, tradeNumbers));
        return new BalanceLogPage(
            found.getContent().stream().map(log -> BalanceLogView.from(
                log,
                log.getTradeNo() != null && ownedTradeNumbers.contains(log.getTradeNo())
            )).toList(),
            found.getTotalElements(),
            pageNumber,
            limit
        );
    }
}
