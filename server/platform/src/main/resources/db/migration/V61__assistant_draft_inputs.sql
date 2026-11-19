-- A1：把「确认时绑定的输入」固化到草稿（改值必须重新确认）。
ALTER TABLE assistant_draft ADD COLUMN inputs_json TEXT NULL;
ALTER TABLE assistant_draft ADD COLUMN inputs_digest CHAR(64) NOT NULL DEFAULT '';
