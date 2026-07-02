-- 로컬에 직접 설치된 MySQL 8을 docker-compose.yml과 동일한 이름으로 세팅.
-- 이렇게 맞춰두면 나중에 Docker로 전환해도 application.yml을 그대로 쓸 수 있다.
--
-- 실행법 (프로젝트 폴더에서, root 비밀번호 입력):
--   mysql -u root -p < db/local-init.sql
--
-- docker-compose.yml 기준값: DB=csquiz / user=csquiz / password=csquiz1234

CREATE DATABASE IF NOT EXISTS csquiz
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

-- localhost 접속용 (직접 설치 MySQL)
CREATE USER IF NOT EXISTS 'csquiz'@'localhost' IDENTIFIED BY 'csquiz1234';
-- 그 외 호스트(향후 Docker/외부 접속) 대비
CREATE USER IF NOT EXISTS 'csquiz'@'%' IDENTIFIED BY 'csquiz1234';

GRANT ALL PRIVILEGES ON csquiz.* TO 'csquiz'@'localhost';
GRANT ALL PRIVILEGES ON csquiz.* TO 'csquiz'@'%';

FLUSH PRIVILEGES;
