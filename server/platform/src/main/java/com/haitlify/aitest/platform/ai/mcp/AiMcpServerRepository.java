package com.haitlify.aitest.platform.ai.mcp;

import java.util.Optional;

import org.springframework.data.repository.ListCrudRepository;

interface AiMcpServerRepository extends ListCrudRepository<AiMcpServer, Long> {

    Optional<AiMcpServer> findByServerId(String serverId);
}
