ALTER TABLE balance_logs
    ADD COLUMN note VARCHAR(500);

ALTER TABLE balance_logs
    DROP CONSTRAINT ck_balance_logs_type,
    ADD CONSTRAINT ck_balance_logs_type CHECK (type IN (
        'OPENING_BALANCE', 'ORDER_PAYMENT', 'ORDER_REFUND',
        'SURPLUS_CREDIT', 'COMMISSION_CREDIT', 'ADMIN_ADJUSTMENT'
    ));

ALTER TABLE balance_logs
    DROP CONSTRAINT ck_balance_logs_amount,
    ADD CONSTRAINT ck_balance_logs_amount CHECK (
        amount_minor <> 0 OR type = 'OPENING_BALANCE'
    ),
    ADD CONSTRAINT ck_balance_logs_sign CHECK (
        (type = 'OPENING_BALANCE' AND amount_minor >= 0)
        OR (type = 'ORDER_PAYMENT' AND amount_minor < 0)
        OR (type IN ('ORDER_REFUND', 'SURPLUS_CREDIT', 'COMMISSION_CREDIT')
            AND amount_minor > 0)
        OR (type = 'ADMIN_ADJUSTMENT' AND amount_minor <> 0)
    );
