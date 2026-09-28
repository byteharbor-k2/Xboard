package com.sinx.platform.stats.repository;

import java.time.LocalDate;
import java.util.UUID;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sinx.platform.stats.domain.TrafficDaily;
import com.sinx.platform.stats.domain.TrafficDailyId;

/**
 * Reader and writer of the daily traffic ledger.
 *
 * All analytics are native aggregate queries: the full rows never need to sit
 * in memory, and the day window filters straight into a plain index scan.
 */
public interface TrafficDailyRepository
    extends JpaRepository<TrafficDaily, TrafficDailyId> {

    /**
     * Adds deltas to a (account, node, day) row, creating it first if needed.
     *
     * Atomic so two reports flushing in the same instant both survive the
     * update, whatever order the rows land in. Native because a portable JPQL
     * upsert does not exist.
     */
    @Modifying
    @Query(value = """
        insert into traffic_daily
            (user_id, node_id, day, upload_bytes, download_bytes, billed_bytes)
        values (:userId, :nodeId, :day, :uploaded, :downloaded, :billed)
        on conflict (user_id, node_id, day) do update set
            upload_bytes = traffic_daily.upload_bytes + excluded.upload_bytes,
            download_bytes = traffic_daily.download_bytes + excluded.download_bytes,
            billed_bytes = traffic_daily.billed_bytes + excluded.billed_bytes
        """, nativeQuery = true)
    int upsertDaily(
        @Param("userId") UUID userId,
        @Param("nodeId") long nodeId,
        @Param("day") LocalDate day,
        @Param("uploaded") long uploaded,
        @Param("downloaded") long downloaded,
        @Param("billed") long billed
    );

    /**
     * Site-wide totals over a day window. Nulls are folded to zero because a
     * window before the table existed simply means "no traffic yet".
     */
    @Query(nativeQuery = true, value = """
        select
            coalesce(sum(t.upload_bytes), 0) as uploadBytes,
            coalesce(sum(t.download_bytes), 0) as downloadBytes,
            coalesce(sum(t.billed_bytes), 0) as billedBytes
        from traffic_daily t
        where t.day >= :from
        """)
    TotalsRow totalsSince(@Param("from") LocalDate from);

    /** The nodes that actually carried traffic, heaviest first. */
    @Query(nativeQuery = true, value = """
        select
            t.node_id as nodeId,
            coalesce(n.name, t.node_id::text) as label,
            coalesce(sum(t.upload_bytes + t.download_bytes), 0) as totalBytes,
            coalesce(sum(t.upload_bytes), 0) as uploadBytes,
            coalesce(sum(t.download_bytes), 0) as downloadBytes,
            coalesce(sum(t.billed_bytes), 0) as billedBytes
        from traffic_daily t
        left join proxy_nodes n on n.id = t.node_id
        where t.day >= :from
        group by t.node_id, n.name
        order by totalBytes desc
        limit :limit
        """)
    List<NodeRankRow> nodeRanking(
        @Param("from") LocalDate from,
        @Param("limit") int limit
    );

    /** The accounts that actually used the platform, heaviest first. */
    @Query(nativeQuery = true, value = """
        select
            t.user_id as userId,
            coalesce(nullif(u.display_name, ''), u.email, t.user_id::text) as label,
            coalesce(sum(t.upload_bytes + t.download_bytes), 0) as totalBytes,
            coalesce(sum(t.billed_bytes), 0) as billedBytes
        from traffic_daily t
        left join users u on u.id = t.user_id
        where t.day >= :from
        group by t.user_id, u.display_name, u.email
        order by totalBytes desc
        limit :limit
        """)
    List<UserRankRow> userRanking(
        @Param("from") LocalDate from,
        @Param("limit") int limit
    );

    /**
     * Every node slice of one account over a day window, for the account's own
     * traffic page. Deleted nodes answer with the bare id as their name.
     */
    @Query(nativeQuery = true, value = """
        select
            t.day as day,
            coalesce(n.name, t.node_id::text) as nodeName,
            t.upload_bytes as uploadBytes,
            t.download_bytes as downloadBytes,
            t.billed_bytes as billedBytes
        from traffic_daily t
        left join proxy_nodes n on n.id = t.node_id
        where t.user_id = :userId
          and t.day >= :from
        order by t.day desc, t.node_id asc
        limit :limit
        """)
    List<ViewerTrafficRow> trafficOfViewer(
        @Param("userId") UUID userId,
        @Param("from") LocalDate from,
        @Param("limit") int limit
    );

    /** Shape of {@link #totalsSince}. */
    interface TotalsRow {
        long getUploadBytes();

        long getDownloadBytes();

        long getBilledBytes();
    }

    /** Shape of {@link #nodeRanking}. */
    interface NodeRankRow {
        long getNodeId();

        String getLabel();

        long getTotalBytes();

        long getUploadBytes();

        long getDownloadBytes();

        long getBilledBytes();
    }

    /** Shape of {@link #userRanking}. */
    interface UserRankRow {
        UUID getUserId();

        String getLabel();

        long getTotalBytes();

        long getBilledBytes();
    }

    /** Shape of {@link #trafficOfViewer}. */
    interface ViewerTrafficRow {
        LocalDate getDay();

        String getNodeName();

        long getUploadBytes();

        long getDownloadBytes();

        long getBilledBytes();
    }
}
