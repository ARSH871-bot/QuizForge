package com.quizforge.identity.repo;

import com.quizforge.identity.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    @Query("SELECT a FROM Account a WHERE LOWER(a.email) = LOWER(:email)")
    Optional<Account> findByEmailIgnoreCase(@Param("email") String email);
}
