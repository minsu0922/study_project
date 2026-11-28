ALTER TABLE document ADD COLUMN topic_id BIGINT NULL;
ALTER TABLE document ADD CONSTRAINT fk_document_topic
    FOREIGN KEY (topic_id) REFERENCES topic_queue (id) ON DELETE SET NULL;
ALTER TABLE generated_document_draft ADD COLUMN topic_id BIGINT NULL;
