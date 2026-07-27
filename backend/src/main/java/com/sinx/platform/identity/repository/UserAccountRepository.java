package com.sinx.platform.identity.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sinx.platform.identity.domain.UserAccount;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    boolean existsByEmail(String email);

    @EntityGraph(attributePaths = "roles")
    @Query("select user from UserAccount user where user.email = :email")
    Optional<UserAccount> findWithRolesByEmail(@Param("email") String email);

    @EntityGraph(attributePaths = "roles")
    @Query("select user from UserAccount user where user.id = :id")
    Optional<UserAccount> findWithRolesById(@Param("id") UUID id);
}
