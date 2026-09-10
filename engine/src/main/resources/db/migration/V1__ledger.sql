-- O razao. Toda linha ingerida tem destino registrado e rastreavel ate o
-- arquivo e o numero da linha.

create table receivables (
    id           text        primary key,
    our_number   text        not null,
    amount_cents bigint      not null,
    due_date     date        not null,
    payer        text        not null
);

create table import_batches (
    id           bigserial   primary key,
    -- SHA-256 do conteudo. UNIQUE e a idempotencia: o mesmo arquivo entra uma
    -- vez so, mesmo que dois processos o submetam ao mesmo tempo.
    file_digest  char(64)    not null unique,
    filename     text        not null,
    layout_name  text        not null,
    -- NOT NULL, e preenchidos com o total real: a linha do lote so e inserida
    -- no fim da transacao, depois de contar. Nao existe lote sem contagem
    -- correta, e isso e garantia do banco, nao promessa da aplicacao.
    line_count   integer     not null,
    total_cents  bigint      not null,
    imported_at  timestamptz not null default now()
);

create table ledger_entries (
    id                    bigserial primary key,
    -- DEFERRABLE porque as linhas sao gravadas ANTES da linha do lote: assim o
    -- lote nasce ja com a contagem conferida, em vez de nascer zerado e ser
    -- corrigido depois. Nao ha estado 'PROCESSING' porque nao ha meio-termo
    -- observavel -- a transacao e uma so, e falha no meio nao deixa lote
    -- concluido porque nao deixa lote nenhum.
    batch_id              bigint    not null references import_batches (id)
                                        on delete cascade
                                        deferrable initially deferred,
    -- Numero da linha no arquivo. E o que o operador confere contra o papel.
    line                  integer   not null,
    status                text      not null
        check (status in ('MATCHED', 'NEEDS_REVIEW', 'UNMATCHED', 'REJECTED')),

    our_number            text,
    amount_cents          bigint,
    paid_at               date,
    counterparty          text,

    matched_receivable_id text      references receivables (id),
    match_reason          text,

    occurrence_code       text,
    occurrence_params     jsonb,

    -- Duas linhas identicas no MESMO arquivo sao dois pagamentos legitimos e
    -- precisam continuar sendo duas. Por isso a chave de idempotencia e
    -- (lote, linha) e nao o conteudo da linha: deduplicar por conteudo apagaria
    -- dinheiro de verdade.
    constraint one_row_per_line unique (batch_id, line),

    -- Linha valida tem valor e data; linha rejeitada tem codigo. Nao existe
    -- meio-termo, e o banco recusa o meio-termo em vez de confiar no app.
    constraint valid_entry_is_complete check (
        status = 'REJECTED' or (amount_cents is not null and paid_at is not null)
    ),
    constraint rejected_entry_has_code check (
        status <> 'REJECTED' or occurrence_code is not null
    ),
    constraint matched_entry_has_receivable check (
        (status = 'MATCHED') = (matched_receivable_id is not null)
    )
);

-- Sem indice de desempenho aqui de proposito. Indice so entra com plano medido
-- antes e depois (EXPLAIN ANALYZE, BUFFERS), registrado em MEASUREMENTS.md.
-- Os indices acima existem por CORRECAO -- sao restricoes, nao otimizacao.
