-- 知识库列表重做（WORK-089）：可见性、主题配色、标签。
ALTER TABLE note_books
    ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'private' AFTER description,
    ADD COLUMN accent_color VARCHAR(16) NULL AFTER visibility,
    ADD COLUMN tags VARCHAR(500) NULL AFTER accent_color;
