package com.irvin.inventory.user;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByRole(Role role);

    @Query("select u.id from User u where u.active = true and u.role in :roles")
    List<Long> findActiveIdsByRoleIn(Collection<Role> roles);

    // Sellers now, and anyone who sold before a role change: their sales and drawers stay filterable.
    // Each EXISTS stops at the first sale through ix_sale_user_date.
    @Query(nativeQuery = true, value = """
            SELECT * FROM app_user u
            WHERE u.role = 'SELLER' OR EXISTS (SELECT 1 FROM sale s WHERE s.user_id = u.id)
            ORDER BY u.name, u.id""")
    List<User> findSellers();

    // Read on every authenticated request (CredentialsVersionValidator): just the column, by primary key.
    @Query("select u.credentialsVersion from User u where u.id = :id")
    Optional<Integer> findCredentialsVersionById(Long id);

    // Prevents lost updates (e.g. an admin edit overwriting a concurrent password change).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(Long id);

    // Locked in id order so concurrent "last admin" checks serialize without deadlocks.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.role = :role and u.active = true order by u.id")
    List<User> lockActiveByRole(Role role);
}
