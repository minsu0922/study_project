-- 테스트 전용 DB. 컨테이너가 빈 볼륨으로 처음 뜰 때 한 번만 실행된다.
-- 이미 쓰던 볼륨에는 적용되지 않으니 그때는 root로 직접 실행한다(docs/07).
CREATE DATABASE IF NOT EXISTS csquiz_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL PRIVILEGES ON csquiz_test.* TO 'csquiz'@'%';
