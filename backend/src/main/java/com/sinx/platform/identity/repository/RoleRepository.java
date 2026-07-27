package com.sinx.platform.identity.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sinx.platform.identity.domain.Role;

public interface RoleRepository extends JpaRepository<Role, String> {
}
