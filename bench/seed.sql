CREATE TABLE posts (id bigint PRIMARY KEY, title text NOT NULL, body text NOT NULL, created_at timestamptz NOT NULL);
INSERT INTO posts SELECT g, '글 ' || g, repeat('본문 ', 200), now() - g * interval '1 minute' FROM generate_series(1, 1000) g;
