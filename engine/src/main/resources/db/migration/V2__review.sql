-- A fila de revisao humana: o que o motor recusou decidir sozinho.

-- Os candidatos que o motor encontrou e nao desempatou. Sem isto a tela de
-- revisao mandaria o operador procurar o titulo na mao -- ou seja, mandaria
-- fazer de novo o trabalho que o motor ja tinha feito.
create table match_candidates (
    entry_id      bigint not null references ledger_entries (id) on delete cascade,
    receivable_id text   not null references receivables (id),
    -- Ordem em que o motor os apresentou. E estavel (por id), e a tela mostra
    -- na mesma ordem para o operador conferir contra o razao.
    position      int    not null,

    primary key (entry_id, receivable_id)
);

create index match_candidates_by_entry on match_candidates (entry_id, position);

-- A decisao humana fica NA LINHA, e nao numa tabela de eventos: o razao precisa
-- responder "qual e o destino desta linha" em uma leitura. Quem decidiu e
-- quando fica registrado; o motivo original (`match_reason`) NAO e sobrescrito,
-- senao a tela perde a informacao de por que aquilo virou revisao.
alter table ledger_entries
    add column decided_at timestamptz,
    add column decided_reason text;

-- Linha decidida tem que ter saido de NEEDS_REVIEW: decisao sobre linha que o
-- motor ja tinha resolvido sozinho e incoerencia, e o banco recusa.
alter table ledger_entries
    add constraint decided_entry_is_resolved check (
        decided_at is null or status in ('MATCHED', 'UNMATCHED')
    );
