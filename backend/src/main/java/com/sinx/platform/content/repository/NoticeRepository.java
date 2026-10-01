package com.sinx.platform.content.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sinx.platform.content.domain.Notice;

public interface NoticeRepository extends JpaRepository<Notice, UUID> {

    /**
     * The dashboard's carousel order, exactly like the original fetch:
     * sort ASC then newest id first among the shown ones.
     */
    List<Notice> findByShownTrueOrderBySortAscIdDesc();
}
