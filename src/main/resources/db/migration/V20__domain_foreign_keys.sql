-- V20__domain_foreign_keys.sql
-- 분야 등록부(docs/superpowers/specs/2026-09-22-domain-registry-design.md 4.2).
-- enum이 컴파일 때 막던 "없는 분야"를 이제 DB가 막는다. ON DELETE는 기본값(RESTRICT) —
-- 이것이 "내용이 있는 분야는 지울 수 없다"(사용자 결정)를 DB 수준에서 강제한다.
--
-- [외래키 전에 고아 코드를 채우는 이유]
-- V19만 적용되고 앱이 한 번도 안 뜬 DB에서는 domain_setting이 비어 있다. 그때 내용 표에 행이
-- 있으면 외래키 추가가 실패한다. 콘텐츠를 심는 것이 아니라 이미 있는 데이터가 스스로 일관되게
-- 만드는 것이라 "Flyway엔 스키마만"(docs/11)의 취지와 어긋나지 않는다. 채운 행은 꺼져 있고
-- 이름이 코드 글자 그대로라, 관리 화면에서 한눈에 "자동으로 채워진 줄"임이 보인다.
-- 2026-09-22 실측으로 로컬 DB에는 채울 것이 0건이다.
INSERT INTO domain_setting (domain, enabled, sort_order, display_name, hint, created_at, updated_at)
SELECT o.domain, FALSE,
       1000 + ROW_NUMBER() OVER (ORDER BY o.domain),
       o.domain, NULL, NOW(6), NOW(6)
FROM (SELECT domain FROM problem
      UNION SELECT domain FROM document
      UNION SELECT domain FROM generated_problem_draft
      UNION SELECT domain FROM generated_document_draft
      UNION SELECT domain FROM topic_queue) o
WHERE o.domain NOT IN (SELECT domain FROM domain_setting);

ALTER TABLE problem                  ADD CONSTRAINT fk_problem_domain FOREIGN KEY (domain) REFERENCES domain_setting (domain);
ALTER TABLE document                 ADD CONSTRAINT fk_document_domain FOREIGN KEY (domain) REFERENCES domain_setting (domain);
ALTER TABLE generated_problem_draft  ADD CONSTRAINT fk_gpd_domain FOREIGN KEY (domain) REFERENCES domain_setting (domain);
ALTER TABLE generated_document_draft ADD CONSTRAINT fk_gdd_domain FOREIGN KEY (domain) REFERENCES domain_setting (domain);
ALTER TABLE topic_queue              ADD CONSTRAINT fk_topic_domain FOREIGN KEY (domain) REFERENCES domain_setting (domain);
