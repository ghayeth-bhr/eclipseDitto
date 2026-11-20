package org.naruprof.twin.dal.jpa.repo;

import org.naruprof.twin.dal.jpa.entity.CheckpointEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataCheckpointRepository extends JpaRepository<CheckpointEntity, String> {
}
