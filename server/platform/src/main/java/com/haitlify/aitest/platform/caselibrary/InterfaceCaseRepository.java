package com.haitlify.aitest.platform.caselibrary;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.ListCrudRepository;

interface InterfaceCaseRepository extends ListCrudRepository<InterfaceCaseRecord, Long> {

    List<InterfaceCaseRecord> findByProjectIdOrderByRunOrderAscSavedAtDesc(Long projectId);

    Optional<InterfaceCaseRecord> findByProjectIdAndLibraryId(Long projectId, String libraryId);
}
