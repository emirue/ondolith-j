package dev.measure.bench;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "posts")
public class Post {
	@Id
	private Long id;
	private String title;
	private String body;
	private OffsetDateTime createdAt;

	public Long getId() { return id; }
	public String getTitle() { return title; }
	public String getBody() { return body; }
	public OffsetDateTime getCreatedAt() { return createdAt; }
}
