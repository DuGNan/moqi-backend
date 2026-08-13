ALTER TABLE chapter_conversation_messages
    ADD COLUMN interaction_json JSON NULL AFTER referenced_message_id,
    ADD COLUMN interaction_response_json JSON NULL AFTER interaction_json;
