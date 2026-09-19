package com.sinx.platform.identity.repository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.identity.domain.UserStatus;

import jakarta.persistence.LockModeType;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    boolean existsByEmail(String email);

    @EntityGraph(attributePaths = "roles")
    @Query("select user from UserAccount user where user.email = :email")
    Optional<UserAccount> findWithRolesByEmail(@Param("email") String email);

    @EntityGraph(attributePaths = "roles")
    @Query("select user from UserAccount user where user.id = :id")
    Optional<UserAccount> findWithRolesById(@Param("id") UUID id);

    long countByServerGroupId(Long serverGroupId);

    /**
     * The administrator's user list.
     *
     * Takes an already-lowercased LIKE pattern and the statuses to include,
     * rather than nullable filters: passing {@code "%"} and every status means
     * "no filter", which keeps one query serving all four combinations without
     * null-typed parameters for the database to guess at.
     */
    @Query("""
        select user from UserAccount user
        where lower(user.email) like :emailPattern
          and user.status in :statuses
        order by user.nodeUserId desc
        """)
    Page<UserAccount> adminSearch(
        @Param("emailPattern") String emailPattern,
        @Param("statuses") Collection<UserStatus> statuses,
        Pageable pageable
    );

    /**
     * Resolves the account behind a subscription link. The token is the only
     * thing the entry point has to go on, so this is the whole lookup.
     */
    @EntityGraph(attributePaths = "roles")
    Optional<UserAccount> findBySubscriptionToken(String subscriptionToken);

    /**
     * Reads an account for a balance or subscription change under a row lock,
     * as the original panel does before opening an order.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from UserAccount user where user.id = :id")
    Optional<UserAccount> findByIdForUpdate(@Param("id") UUID id);
}
