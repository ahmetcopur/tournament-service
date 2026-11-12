package io.github.ahmetcopur.tournament.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String username;

    @Column(nullable = false, length = 2)
    private String country;

    @Column(nullable = false)
    private int level;

    @Column(nullable = false)
    private long coins;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected User() {
    }

    public User(String username, String country, long coins, Instant createdAt) {
        this.username = username;
        this.country = country;
        this.level = 1;
        this.coins = coins;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getCountry() {
        return country;
    }

    public int getLevel() {
        return level;
    }

    public long getCoins() {
        return coins;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
