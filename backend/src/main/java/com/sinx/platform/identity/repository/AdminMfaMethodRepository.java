package com.sinx.platform.identity.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sinx.platform.identity.domain.AdminMfaMethod;

import jakarta.persistence.LockModeType;

public interface AdminMfaMethodRepository
    extends JpaRepository<AdminMfaMethod, UUID> {

    @Query("""
        select case when count(method) > 0 then true else false end
        from AdminMfaMethod method
        where method.user.id = :userId
          and method.enabledAt is not null
        """)
    boolean existsEnabledByUserId(@Param("userId") UUID userId);

    @EntityGraph(attributePaths = {"user", "user.roles"})
    @Query("""
        select method
        from AdminMfaMethod method
        where method.user.id = :userId
        """)
    Optional<AdminMfaMethod> findByUserId(@Param("userId") UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select method
        from AdminMfaMethod method
        join fetch method.user user
        left join fetch user.roles
        where method.user.id = :userId
        """)
    Optional<AdminMfaMethod> findForUpdateByUserId(
        @Param("userId") UUID userId
    );
}
