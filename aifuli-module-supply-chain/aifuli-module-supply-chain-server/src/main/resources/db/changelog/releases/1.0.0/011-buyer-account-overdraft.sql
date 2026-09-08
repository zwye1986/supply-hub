-- 一期弱化采购商可用余额门槛：允许透支冻结与负余额，仅保证 frozen >= 0。
-- 详见 docs/superpowers/specs/2026-08-17-supply-chain-phase1-buyer-account-overdraft.md
ALTER TABLE sc_buyer_account
    DROP CHECK ck_sc_buyer_account_amount;

ALTER TABLE sc_buyer_account
    ADD CONSTRAINT ck_sc_buyer_account_amount CHECK (frozen >= 0);

ALTER TABLE sc_buyer_account
    MODIFY COLUMN balance BIGINT NOT NULL DEFAULT 0
        COMMENT '账户总余额，单位为分；一期允许为负（透支扣减后）';

ALTER TABLE sc_buyer_account
    MODIFY COLUMN frozen BIGINT NOT NULL DEFAULT 0
        COMMENT '账户已冻结金额，单位为分；一期允许大于 balance';

ALTER TABLE sc_account_ledger
    DROP CHECK ck_sc_account_ledger_balance;

ALTER TABLE sc_account_ledger
    DROP CHECK ck_sc_account_ledger_frozen;

ALTER TABLE sc_account_ledger
    DROP CHECK ck_sc_account_ledger_entry_semantics;

ALTER TABLE sc_account_ledger
    ADD CONSTRAINT ck_sc_account_ledger_frozen CHECK (frozen_before >= 0 AND frozen_after >= 0);

ALTER TABLE sc_account_ledger
    ADD CONSTRAINT ck_sc_account_ledger_entry_semantics CHECK (
        (entry_type = 'DEPOSIT' AND balance_delta >= 0 AND frozen_delta = 0)
        OR (entry_type = 'FREEZE' AND balance_delta = 0 AND frozen_delta > 0)
        OR (entry_type = 'COMMIT' AND balance_delta < 0 AND frozen_delta = balance_delta)
        OR (entry_type = 'RELEASE' AND balance_delta = 0 AND frozen_delta < 0)
        OR (entry_type = 'REFUND' AND balance_delta > 0 AND frozen_delta = 0)
        OR (entry_type = 'ADJUSTMENT' AND balance_delta <> 0 AND frozen_delta = 0)
    );
