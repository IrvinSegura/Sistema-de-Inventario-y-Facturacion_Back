-- In-app notifications of things that happened to a user's work or account (a sale of theirs
-- cancelled, a drawer assigned to them...). Written after the change commits; the web renders the text
-- from type and the values below, so nothing here is in a language. A new table, so its constraints need
-- no NOT VALID step.
CREATE TABLE notification (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recipient_id  BIGINT        NOT NULL,
    -- Who caused it; null for notifications with no person behind them.
    actor_id      BIGINT,
    type          VARCHAR(40)   NOT NULL,
    -- What it is about (sale, purchase, cash drawer) and the values shown with it; which ones a type
    -- fills is documented in NotificationType.
    subject_id    BIGINT,
    subject_label VARCHAR(150),
    amount        NUMERIC(12,2),
    reason        VARCHAR(255),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    read_at       TIMESTAMPTZ,
    CONSTRAINT fk_notification_recipient FOREIGN KEY (recipient_id) REFERENCES app_user (id),
    CONSTRAINT fk_notification_actor FOREIGN KEY (actor_id) REFERENCES app_user (id),
    CONSTRAINT ck_notification_type CHECK (type IN ('SALE_CANCELLED', 'PURCHASE_CANCELLED',
        'CASH_DRAWER_ASSIGNED', 'CASH_DRAWER_CLOSED', 'EMAIL_CHANGED', 'PHONE_CHANGED', 'PASSWORD_RESET'))
);

-- A user's notifications, newest first.
CREATE INDEX ix_notification_recipient_date ON notification (recipient_id, created_at, id);
-- The unread counter, polled by every signed-in user, and the unread-only list.
CREATE INDEX ix_notification_recipient_unread ON notification (recipient_id, created_at, id) WHERE read_at IS NULL;
CREATE INDEX ix_notification_actor ON notification (actor_id);
