-- 崩溃窗口的显式证据列。
--
-- SUBMITTED 只表示「这一轮的尝试已经写进账本」，不表示网关受理过：
-- 平台按「先记账、后提交」的顺序写，所以进程在 prompt.submit 之前/之后死掉时，账本里一定有一行
-- SUBMITTED。accepted_at 非空才证明 prompt.submit 真的被受理（此时随后的 message.complete 才可归因于
-- 本轮）；为空且仍是 SUBMITTED 的行只可能是「受理结果未知」的在途尝试，必须按 UNKNOWN 对账，
-- 既不允许被读成事实终态，也不允许因此把同一 request_id 二次提交到 wire。
ALTER TABLE assistant_conversation_messages ADD COLUMN accepted_at TIMESTAMP(6) NULL;
