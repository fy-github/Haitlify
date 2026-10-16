package com.haitlify.aitest.platform.auth;

import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

interface PlatformUserRepository extends CrudRepository<PlatformUser, Long> {

    Optional<PlatformUser> findByUsername(String username);
}
