create table if not exists mined_blocks
(
    height int4 primary key not null,
    reward int8             not null
);

create table if not exists payouts
(
    id                 int4 primary key auto_increment not null,
    from_height        int4                            not null,
    to_height          int4                            not null,
    reward             int8                            not null,
    generating_balance int8                            not null,
    active_leases      longvarbinary                   not null,
    tx_id              char(44),
    tx_height          int4,
    confirmed          bool                            not null default (false)
);

alter table if exists payouts
    alter column reward rename to amount;
alter table if exists payouts
    drop column active_leases, tx_id, tx_height, confirmed;

create table if not exists payout_transactions
(
    id          char(44) primary key not null,
    payout_id   int4 references payouts (id),
    transaction varbinary            not null,
    height      int4
);

create table if not exists leases
(
    id          char(44) primary key not null,
    transaction varbinary            not null,
    height      int4                 not null
);

create table if not exists payout_leases
(
    id       int4 references payouts (id),
    lease_id char(44) references leases (id)
);
