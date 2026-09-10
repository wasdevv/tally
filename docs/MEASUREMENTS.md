# Medições

Todo número citado no README, no currículo ou em entrevista sai daqui, com o
comando que reproduz. Sem isso a bullet é afirmação; com isso é evidência.

**Nada nesta página foi estimado.** Onde não houve medição, está escrito que não
houve — e a lacuna vale mais que um número plausível.

## Protocolo

Duas disciplinas, e as duas mudam o resultado:

1. **Descarte de warmup.** A JVM compila em runtime. Os primeiros segundos medem
   o JIT, não o código. São 5 execuções, as 2 primeiras descartadas, mediana das
   3 restantes.
2. **Contraste.** "25.663 linhas/s" isolado não diz nada. Contra a alternativa
   que foi descartada, diz tudo.

Benchmark **não roda no CI**: runner compartilhado tem vizinho barulhento, e
número de benchmark em CI é ruído que ninguém confia. É ritual manual.

Heap medido com `-Xms64m -Xmx1g` fixo. Heap elástico mede a política do GC, não
o programa.

---

## Ingestão — CNAB 400, 100k lançamentos, 38 MB

```
Comando:   ./gradlew measure
Máquina:   AMD Ryzen 7 5800XT (16 threads), 15 GB RAM, WSL2 sobre Linux 6.6
JVM:       OpenJDK 21.0.12, -Xms64m -Xmx1g
Data:      2026-09-10  ·  Commit: 4e82814
Método:    5 execuções, descartadas as 2 primeiras (warmup de JIT), mediana das 3
Corpus:    gerado pelo próprio repositório, 5.000 recebíveis carregados
```

| Estratégia | Vazão | Pico de heap |
|---|---:|---:|
| Streaming, uma linha por vez | **25.663 linhas/s** | **34 MB** |
| Arquivo inteiro em memória | 24.306 linhas/s | 127 MB |

**O que o número diz, e o que ele não diz.**

O ganho do streaming é **memória, não velocidade**: 34 MB contra 127 MB, com a
vazão praticamente igual (a diferença de 5% está dentro do ruído entre
execuções). Faz sentido — as duas fazem o mesmo trabalho por linha; a diferença
é o que fica retido enquanto isso.

Isso contradiz o rascunho de bullet que dizia "importa a X linhas/s **e** segura
o heap". A parte de heap se sustenta; a de velocidade não. O número medido
manda.

E o número de heap importa porque ele é o que decide se o arquivo de 2 GB do
cliente sobe ou derruba o processo: em memória, o pico cresce com o arquivo; em
streaming, não.

## Ingestão ponta a ponta — com o INSERT no caminho

A medição acima para **antes do banco**: ela mede parser mais casamento. Esta
inclui transação, advisory lock e escrita — e é ela que descreve o que o sistema
faz de fato.

```
Comando:   ./gradlew measurePersisted
Máquina:   AMD Ryzen 7 5800XT (16 threads), 15 GB RAM, WSL2 sobre Linux 6.6
Banco:     PostgreSQL 16 em container (Testcontainers), transação única
Data:      2026-09-10  ·  Commit: f3175bc
Método:    5 execuções, descartadas as 2 primeiras, mediana das 3, banco limpo a cada execução
```

| | Vazão |
|---|---:|
| Só parser e casamento | 25.663 linhas/s |
| **Com persistência** | **9.405 linhas/s** |

**O banco custa 2,7× do tempo total.** É o número honesto para citar: quem
disser "importa a 25 mil linhas/s" está descrevendo a metade do trabalho que não
toca disco.

**Uma armadilha que valeu registrar.** A primeira versão desta medição reusava o
mesmo digest a cada execução — e a idempotência devolvia o lote pronto sem
gravar nada. O resultado teria sido espetacular e teria medido exatamente nada.
Cada execução gera um digest próprio, e há um `check` de que as 100.000 linhas
foram mesmo gravadas.

## Índice por nosso número — antes e depois

O primeiro perfil mostrou vazão idêntica nas duas estratégias e **abaixo do
esperado**. A causa não era o parser: a conciliação refiltrava a lista inteira
de recebíveis a cada linha, fazendo o custo ser O(linhas × recebíveis).

```
Comando:   ./gradlew measure   (mesmo protocolo, mesma máquina, mesmo corpus)
Antes:     commit 4e82814, filtro linear por linha
Depois:    índice por nosso número em Reconciliation
```

| | Vazão (streaming) | Pico de heap |
|---|---:|---:|
| Antes | 10.928 linhas/s | 70 MB |
| Depois | **25.663 linhas/s** | **34 MB** |
| | **2,3×** | **−51%** |

Otimização que muda resultado é bug, então a equivalência é **provada, não
afirmada**: `ReconciliationSpec` compara o razão indexado com o de uma
implementação ingênua de varredura total, sobre 800 arquivos gerados. Conferido
por mutação — deixar o índice sair de sincronia com a lista reprova a suíte.

## Suítes

```
Comando:   ./gradlew test          (domínio + property, sem container)
           ./gradlew integrationTest  (PostgreSQL 16 real via Testcontainers)
           cd console && bin/rspec
Data:      2026-09-10  ·  Commit: 4e82814
```

| Suíte | Exemplos | Tempo | Precisa de container |
|---|---:|---:|---|
| Motor — domínio e property | 99 | ~9 s | não |
| Motor — integração, API e revisão | 32 | ~21 s | sim |
| Console — request, system, serviço | 57 | ~0,9 s | não |
| Console — contrato contra o motor real | 11 | ~1,8 s | sim |
| Navegador — atalhos e drawer (`bin/*-check.rb`) | 12 + 7 | ~10 s | sim, e Chromium |

O loop de TDD é a primeira linha: abaixo de 10 segundos, ou você para de rodar.

As propriedades executam **1.000 casos gerados** cada nas duas de conservação, e
500 e 300 nas de equivalência — o número está no código, não aqui.

## O que ainda NÃO foi medido

Escrito porque lacuna reconhecida vale mais que número inventado:

- **Plano de consulta do razão.** Nenhum índice de desempenho foi criado, então
  não há `EXPLAIN (ANALYZE, BUFFERS)` antes e depois para registrar. Os índices
  que existem no esquema estão lá por **correção** — são restrições, não
  otimização.
- **Percentual de casamento automático sobre corpus real.** Depende de um corpus
  representativo que não existe: todas as fixtures são sintéticas, e medir taxa
  de acerto contra dado que você mesmo gerou mede o gerador.
- **Concorrência sob carga.** O teste de corrida usa 8 corrotinas e prova
  *correção* (uma escrita só), não vazão sob contenção.
