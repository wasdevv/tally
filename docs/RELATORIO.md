# Tally — relatório do projeto

Estado em 2026-09-10, commit `be66b72`. Todo número aqui foi medido nesta
máquina; nenhum foi estimado. Onde não houve medição, está escrito que não houve.

---

## 1. O que o sistema faz

Ingere arquivos de retorno bancário (CNAB 400 de largura fixa e CSV), casa cada
lançamento contra recebíveis esperados e produz um razão auditável onde **toda
linha do arquivo tem destino registrado**: conciliada, aguardando revisão, sem
par, ou rejeitada com motivo. O que o motor recusa decidir sozinho vai para uma
fila humana carregando os candidatos e a razão da dúvida.

Dois processos: um **motor Kotlin/Spring Boot** sobre PostgreSQL 16 decide, e um
**console Rails 8 + Hotwire** apresenta, em inglês e português. Eles conversam
por REST com HMAC — o console não tem tabela do razão e não abre conexão no banco
do motor.

```
docker compose up   →   console em :3000, motor em :8080
```

---

## 2. A invariante que sustenta tudo

```
conciliadas + revisão + sem par + rejeitadas == linhas do arquivo
soma(conciliadas) + soma(revisão) + soma(sem par) == soma das linhas válidas
```

Em quantidade **e** em centavos. É a primeira coisa que existiu no projeto —
antes do parser, antes do banco — e está sob property testing com **1.000 casos
gerados por propriedade**, 3.800 casos por execução da suíte somando todas.

**Verificada por mutação, não por suíte verde.** Descartar silenciosamente uma
linha sem par quebra as duas propriedades; deixar o índice de recebíveis sair de
sincronia quebra a equivalência com a implementação ingênua; remover o advisory
lock quebra o teste de corrida. Cada gate deste projeto foi conferido dessa
forma — um teste que nunca falha não prova nada.

---

## 3. Números medidos

### Ingestão — CNAB 400, 100 mil lançamentos, 38 MB

| Estratégia | Vazão | Pico de heap |
|---|---:|---:|
| Streaming, uma linha por vez | 25.663 linhas/s | **34 MB** |
| Arquivo inteiro em memória | 24.306 linhas/s | 127 MB |
| **Ponta a ponta, com o `INSERT`** | **9.405 linhas/s** | — |

Três leituras, e as três importam:

1. **O ganho do streaming é memória, não velocidade.** A diferença de vazão está
   dentro do ruído. Isso contradiz o rascunho de bullet que dizia "importa mais
   rápido **e** segura o heap" — a metade de heap se sustenta, a de velocidade
   não. O número medido manda.
2. **O heap é o que decide se o arquivo de 2 GB do cliente sobe ou derruba o
   processo.** Em memória o pico cresce com o arquivo; em streaming, não.
3. **O banco custa 2,7× do tempo total.** Citar 25 mil linhas/s seria descrever a
   metade do trabalho que não toca disco.

Protocolo: 5 execuções, as 2 primeiras descartadas (warmup de JIT), mediana das
3. Heap fixo em `-Xms64m -Xmx1g` — heap elástico mede a política do GC, não o
programa. Benchmark não roda no CI: runner compartilhado tem vizinho barulhento.

### Índice por nosso número — antes e depois

O primeiro perfil mostrou vazão idêntica entre as duas estratégias e abaixo do
esperado. Não era o parser: a conciliação refiltrava a lista inteira de
recebíveis **a cada linha**, O(linhas × recebíveis).

| | Vazão | Pico de heap |
|---|---:|---:|
| Antes | 10.928 linhas/s | 70 MB |
| Depois | 25.663 linhas/s | 34 MB |
| | **2,3×** | **−51%** |

Otimização que muda resultado é bug, então a equivalência é **provada**: o razão
indexado é comparado com o de uma implementação ingênua de varredura total sobre
800 arquivos gerados.

### Tamanho e suítes

| | |
|---|---:|
| Kotlin de produção | 2.522 linhas em 29 arquivos |
| `domain/` (sem framework nenhum) | 400 linhas |
| Cobertura de `domain/` | 96,3% de linhas, piso de 80% no CI |
| Migrations | 2 |
| Decisões registradas | 40 |

| Suíte | Exemplos | Tempo | Container |
|---|---:|---:|---|
| Motor — domínio e property | 99 | ~9 s | não |
| Motor — integração, API e revisão | 32 | ~21 s | sim |
| Console — request, system, serviço | 57 | ~0,9 s | não |
| Console — contrato contra o motor real | 11 | ~1,8 s | sim |
| Navegador — atalhos e drawer | 19 | ~10 s | sim, e Chromium |

O loop de TDD é a primeira linha: abaixo de 10 segundos, ou você para de rodar.

### Layout de banco novo

**11 linhas de declaração**, zero linha de código. Medido: `SecondBankLayoutSpec`
declara um banco fictício com posições diferentes **e** discriminador de detalhe
diferente (`"E"` em vez de `"1"`), e lê o arquivo dele sem nenhuma mudança em
`parsing/`.

---

## 4. As decisões que valem defender

Quarenta estão em [DECISIONS.md](DECISIONS.md), cada uma nomeando o que foi
descartado. As sete que carregam o projeto:

**Dinheiro é `value class` sobre `Long`.** Em runtime é um `long` — segurança de
tipo com zero alocação. E `plus`/`minus`/`abs` usam `Math.*Exact`: wraparound
transforma um total gigante em negativo, e a conservação fecharia em cima de um
número errado. `kotlin.math.abs(Long.MIN_VALUE)` tem a mesma armadilha.

**`domain` não importa framework, e isso é um teste.** Konsist quebra o build se
alguém escrever `@Autowired` no `Reconciler`. É o que faz 3.800 casos de property
testing rodarem em milissegundos: não há container no caminho.

**O motor nunca emite texto para humano.** Ocorrência é `{line, code, params}`, e
o console produz a frase. Um terceiro idioma não toca uma linha de Kotlin. A
regra vale também para os **parâmetros**: `paidAt` é traduzido como o código é,
porque identificador de máquina não vai para a tela do operador.

**O separador decimal é propriedade do layout, nunca do locale.** Um analista com
a interface em inglês importando CSV brasileiro precisa que `1.234,56` vire
123456 centavos. Travado por spec que roda sob `Locale.US`, `pt-BR` e `GERMANY`.

**O motor só casa sozinho quando o título identifica o lançamento.** Bater apenas
o valor é palpite, e palpite em conciliação põe dinheiro na conta errada. Vai
para a fila com os candidatos e o motivo — mesmo quando há só um candidato.

**Decisão de revisão nunca remove a linha do razão.** "Nenhum destes" leva a
`UNMATCHED`. A conservação é medida contra o **arquivo**: se o operador pudesse
apagar, o razão passaria a fechar contra si mesmo.

**Importação inteira em uma transação, sem estado `PROCESSING`.** As linhas são
gravadas antes do lote (FK deferida); o lote nasce no fim com a contagem já
conferida. Falha no meio não deixa lote concluído porque não deixa lote nenhum.

---

## 5. Os bugs que só apareceram rodando

Esta seção existe porque é o que separa "a suíte passa" de "o sistema funciona".
Todos foram encontrados com o sistema de pé, e nenhum seria pego pela suíte.

| Defeito | Por que a suíte não via |
|---|---|
| HMAC recusava toda chamada **filtrada** | Só chamadas sem query string eram assinadas nos testes |
| Upload chegava **vazio** ao controller | Drenar o corpo para assinar deixava o multipart sem stream |
| Redirect após importar ia para **https** sem TLS na pilha | O `GET` respondia 200; nenhum teste segue redirect para outro esquema |
| Página rolava de lado em 320px | O `.sr-only` escapava do container — `overflow-x` não contém `position: absolute` |
| Valor sumia da tabela no mobile | A prioridade de coluna nunca tinha sido medida em viewport estreita |
| A tecla `?` não abria a ajuda | Alvo fora do escopo do controller: para o Stimulus, ele não existe |
| "Revisar" não fazia **nada** em 375px | `display: none` não impede o Turbo de navegar dentro do frame |
| O CI **nunca poderia rodar** | O diretório `.github/` estava fora da raiz do repositório |
| `paidAt` cru no meio de frase em português | Os specs codificavam o bug — afirmavam a frase com o identificador dentro |

O último merece nota: três specs **asseguravam o comportamento errado**. Teste
escrito a partir da implementação confirma a implementação, inclusive quando ela
está errada.

---

## 6. O que ficou de fora, e por quê

| Fora | Motivo |
|---|---|
| CNAB 240 e OFX | Dois formatos já provam a DSL. O próprio brief manda cortá-los antes de i18n e medição. |
| GIF do README | Não versionar placeholder alegando demonstração. |
| Kafka, microserviços, event sourcing, k8s, GraphQL | Sem dor que os justifique. O razão já é o log. |
| Multi-tenancy, RBAC, 2FA | Um cliente, um usuário. |
| ORM no motor | O SQL é o assunto; ele fica visível. |
| Geração de código do jOOQ | `SchemaDriftSpec` dá a mesma garantia em 40 linhas, sem exigir Postgres de pé para compilar. |

**Ainda não medido**, e escrito porque lacuna reconhecida vale mais que número
inventado: plano de consulta do razão (nenhum índice de desempenho foi criado —
os que existem são restrições), percentual de casamento automático sobre corpus
real (medir contra dado que você mesmo gerou mede o gerador), e vazão sob
contenção (o teste de corrida prova *correção*, não throughput).

---

## 7. Bullets para o currículo

Os colchetes do rascunho original estão preenchidos **só onde houve medição**.

> **Tally** — Motor de conciliação bancária em Kotlin e Spring Boot sobre
> PostgreSQL 16, ingerindo retornos CNAB 400 e CSV e casando-os contra
> recebíveis esperados: 2.522 linhas de Kotlin, 131 exemplos no motor e 68 no
> console web.
>
> Modelei dinheiro como *value class* sobre centavos inteiros em vez de ponto
> flutuante ou decimal encaixotado — em runtime é um `long`, então segurança de
> tipo custa zero alocação — e as operações estouram em overflow em vez de dar a
> volta, porque um total que faz wraparound vira negativo e a conservação
> fecharia sobre um número errado.
>
> Sustento uma invariante de conservação sob property testing: em 3.800 casos
> gerados por execução, conciliadas mais revisão mais sem par mais rejeitadas
> igualam a contagem de linhas, e os centavos fecham — verificado por mutação,
> não por suíte verde.
>
> Faço parsing de layouts FEBRABAN de largura fixa por uma DSL declarativa em
> vez de offsets espalhados pelo código: um layout de banco novo são **11 linhas
> de dado e nenhuma linha de código**, provado por um teste que declara um banco
> com posições e discriminador diferentes sem tocar no parser.
>
> Streamo a ingestão por Kotlin Flow com backpressure limitado: 100 mil linhas a
> **34 MB de pico de heap** contra **127 MB** lendo o mesmo arquivo em memória —
> e reporto que a vazão é equivalente entre as duas, porque foi isso que a
> medição disse.
>
> Reporto vazão em regime após descartar o warmup da JVM, mediana de cinco
> execuções, e separo o número com persistência (**9.405 linhas/s**) do número só
> de parsing (**25.663**), porque os primeiros segundos de uma execução medem o
> compilador e o segundo número descreve metade do trabalho.
>
> Cortei a conciliação de **10.928 para 25.663 linhas/s** trocando varredura
> linear por índice de título, com a equivalência do razão provada contra uma
> implementação ingênua sobre 800 arquivos gerados — otimização que muda
> resultado é bug.
>
> Mantenho o reprocessamento seguro sob concorrência com digest SHA-256 atrás de
> índice único e advisory lock por arquivo, verificado por 8 corrotinas
> disputando o mesmo arquivo e gravando 100 mil linhas uma única vez.
>
> Mantenho o motor livre de texto para humano: ele emite códigos estáveis com
> parâmetros e o console Rails os renderiza, então um terceiro idioma não toca
> uma linha de Kotlin — com a paridade provada por um teste que lê o `enum` do
> Kotlin e exige tradução nos dois idiomas.
>
> Entrego o console em inglês e português com completude de tradução barrada no
> CI, e faço o parsing decimal do arquivo a partir do layout e não do locale da
> interface, para o mesmo arquivo importar idêntico para os dois operadores.
>
> Encaminho o que o casador não decide para uma fila humana carregando o
> conjunto de candidatos e o motivo em vez de adivinhar, e a decisão nunca
> remove a linha do razão — a conservação é medida contra o arquivo, não contra
> si mesma.
>
> Construí a tabela de conciliação para varredura e não para cards: estado por
> glifo e filete além de cor, algarismos tabulares, atalhos de teclado e layout
> por prioridade de coluna verificado a 320px com `scrollWidth` medido.
>
> Mantive `domain` sem nenhum import de framework, verificado por teste de
> arquitetura que quebra o build — é o que faz a suíte de propriedades rodar em
> milissegundos, sem container no caminho.

---

## 8. Como verificar cada afirmação

```bash
./gradlew test              # 99 exemplos, domínio e property, sem container
./gradlew integrationTest   # 32, PostgreSQL 16 real via Testcontainers
./gradlew check             # + ktlint, detekt, piso de cobertura
./gradlew measure           # vazão e heap: streaming contra memória
./gradlew measurePersisted  # vazão ponta a ponta, com o INSERT

cd console
bin/rspec                   # 68 exemplos
bundle exec i18n-tasks missing && bundle exec i18n-tasks unused
bin/keyboard-check.rb       # 12 verificações num Chromium de verdade
bin/drawer-check.rb         # drawer e Turbo Stream, em dois viewports
```
