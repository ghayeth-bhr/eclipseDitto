package org.naruprof.twin.dal.jpa.repo;

import jakarta.persistence.LockModeType;
import org.naruprof.twin.dal.jpa.entity.LeaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SpringDataLeaseRepository extends JpaRepository<LeaseEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from LeaseEntity l where l.profileId = :id")
    Optional<LeaseEntity> lockById(@Param("id") String profileId);
}
