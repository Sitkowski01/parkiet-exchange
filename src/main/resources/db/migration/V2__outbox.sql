-- Globalna kolejnosc zapisu w dzienniku. Pisze jeden watek (event-writer), transakcja po
-- transakcji, wiec id rosna w kolejnosci commitow -- i w kazdej spolce zgodnie z sequence.
alter table engine_event add column id bigint generated always as identity;
alter table engine_event add constraint engine_event_id_key unique (id);

-- Transactional outbox jako kursor: dziennik zostaje nietkniety (tylko insert), a relay
-- pamieta, do ktorego id doszedl. Kolumna "published_at" w dzienniku znaczylaby drugi zapis
-- kazdego wiersza i puchnace indeksy.
create table outbox_cursor (
    topic         varchar(100) primary key,
    last_event_id bigint not null default 0
);

insert into outbox_cursor (topic) values ('exchange.events');
