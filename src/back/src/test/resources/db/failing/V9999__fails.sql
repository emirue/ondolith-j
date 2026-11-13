-- 시험 전용: 앞부분은 성공하고 뒤에서 실패한다. PostgreSQL 은 DDL 도 트랜잭션이라 앞부분까지 원복돼야 한다(NFR-303).
CREATE TABLE half_done (id int);
SELECT 1 / 0;
