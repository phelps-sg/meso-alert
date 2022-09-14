create database "meso-alert"
    with owner "meso-alert";

create table webhooks
(
    url        varchar not null
        primary key,
    threshold  bigint  not null,
    is_running boolean not null
);

alter table webhooks
    owner to "meso-alert";

create table slack_chat_hooks
(
    channel_id varchar not null
        primary key,
    nonce      varchar not null,
    token      varchar not null,
    threshold  bigint  not null,
    is_running boolean not null
);

alter table slack_chat_hooks
    owner to "meso-alert";

create table slack_teams
(
    team_id            varchar not null
        primary key,
    user_id            varchar not null,
    bot_id             varchar not null,
    nonce              varchar not null,
    access_token       varchar not null,
    team_name          varchar not null,
    registered_user_id varchar
);

alter table slack_teams
    owner to "meso-alert";

create table transaction_updates
(
    id          bigserial
        primary key,
    hash        varchar   not null,
    value       bigint    not null,
    time_stamp  timestamp not null,
    "isPending" boolean   not null
);

alter table transaction_updates
    owner to "meso-alert";

create table slack_slash_command_history
(
    id                    serial
        primary key,
    channel_id            varchar not null,
    command               varchar not null,
    text                  varchar not null,
    team_domain           varchar,
    team_id               varchar not null,
    channel_name          varchar,
    user_id               varchar,
    user_name             varchar,
    is_enterprise_install boolean,
    time_stamp            timestamp
);

alter table slack_slash_command_history
    owner to "meso-alert";

