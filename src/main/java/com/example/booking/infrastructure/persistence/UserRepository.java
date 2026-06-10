package com.example.booking.infrastructure.persistence;

import com.example.booking.domain.user.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
}
