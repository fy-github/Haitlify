-- A1：operation 记录所属 project（端点据此做项目成员校验）；报告决策落库（谁在何时接受了哪一版）。
ALTER TABLE assistant_operation_ledger ADD COLUMN project_id BIGINT NULL;

ALTER TABLE assistant_report ADD COLUMN decision VARCHAR(16) NULL;
ALTER TABLE assistant_report ADD COLUMN decided_by BIGINT NULL;
ALTER TABLE assistant_report ADD COLUMN decided_at TIMESTAMP(3) NULL;
