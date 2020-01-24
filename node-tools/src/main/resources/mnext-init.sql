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
    amount             int8                            not null,
    generating_balance int8                            not null
);

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
    id       int4 references payouts (id) on delete cascade,
    lease_id char(44) references leases (id) on delete cascade
);

create table if not exists state_version
(
    key     varchar(255) primary key not null,
    version int4
);
