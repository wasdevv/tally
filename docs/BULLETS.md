# Tally — dossiê de bullets

Documento **auto-suficiente**: serve para trabalhar as bullets deste projeto sem
abrir o repositório. Estado em 2026-09-10, commit `83d748f`.

Cada bullet vem com: o número e de onde ele saiu, o comando que reproduz, a
decisão técnica por trás, e a pergunta que a bullet convida um entrevistador a
fazer — com a resposta.

**Regra que vale para tudo aqui:** número sem medição não entra. Onde não houve
medição, está escrito que não houve, e isso é deliberado — a seção 5 lista o que
**não** afirmar.

---

## 0. O projeto em cinco linhas

Motor de conciliação bancária. Ingere arquivos de retorno (CNAB 400 de largura
fixa e CSV), casa cada lançamento contra recebíveis esperados e produz um razão
onde **toda linha do arquivo tem destino registrado**: conciliada, em revisão,
sem par, ou rejeitada com motivo.

Dois processos: **Kotlin/Spring Boot sobre PostgreSQL 16** decide; **Rails
8/Hotwire** apresenta, em inglês e português. Conversam por REST com HMAC.

Repositório privado: `github.com/wasdevv/tally`. CI verde nos três jobs.

---

## 1. Posicionamento — a frase de abertura

Antes das bullets, a moldura. Se a pessoa só ler uma linha:

> Motor de conciliação bancária em Kotlin que trata "nenhum centavo pode sumir"
> como invariante verificável, não como intenção.

O domínio não é inventado — CNAB 240/400 e cinco bancos em produção estão no seu
histórico. Isso importa em entrevista: dá para responder "por que assim" sem
inventar.

---

## 2. As bullets, com a evidência de cada uma

Keywords técnicas ficam **dentro** das bullets, nunca só numa lista de skills à
parte — matcher de ATS pontua o termo no contexto da bullet.

---

### B1 — Abertura

> **Tally** — Motor de conciliação bancária em **Kotlin** e **Spring Boot** sobre
> **PostgreSQL 16**, ingerindo retornos **CNAB 400** e **CSV** e casando-os
> contra recebíveis esperados: 2.522 linhas de Kotlin, 131 exemplos no motor e
> 68 no console web.

| | |
|---|---|
| Números | 2.522 linhas de Kotlin de produção (29 arquivos); 99 exemplos rápidos + 32 de integração; 68 no console |
| Reproduz | `./gradlew test`, `./gradlew integrationTest`, `cd console && bin/rspec` |
| Keywords | Kotlin, Spring Boot, PostgreSQL, CNAB, CSV |

**Se perguntarem "por que Spring e não Ktor?"** — sinal de mercado. As vagas de
Kotlin backend pedem Spring; Ktor teria sido mais elegante para dois processos e
menos útil para o objetivo do projeto. É uma escolha de posicionamento assumida,
não técnica.

---

### B2 — Dinheiro como tipo

> Modelei dinheiro como **value class** sobre centavos inteiros em vez de ponto
> flutuante ou decimal encaixotado — em runtime é um `long`, então segurança de
> tipo custa zero alocação — e as operações **estouram em overflow** em vez de
> dar a volta, porque um total que faz wraparound vira negativo e a conservação
> fecharia sobre um número errado.

| | |
|---|---|
| Evidência | `Cents.kt`, 28 linhas; `CentsSpec` com 7 exemplos |
| Reproduz | `./gradlew test --tests '*CentsSpec*'` |
| Keywords | value class, JVM, overflow, tipo |

**A pergunta que ela convida: "por que não BigDecimal?"** — correto na
aritmética, mas aloca por operação e não impede somar reais com dólares, porque
todo valor é o mesmo tipo. `value class` dá a segurança de tipo e some em
runtime.

**A pergunta melhor: "por que `Math.addExact` e não `+`?"** — porque wraparound
em `Long` transforma um total gigante em **negativo**, e a invariante de
conservação passaria a fechar sobre um valor errado. É o único modo de falha que
o projeto existe para impedir. `kotlin.math.abs(Long.MIN_VALUE)` tem a mesma
armadilha: devolve `Long.MIN_VALUE`, que é negativo — por isso `absExact`.

---

### B3 — A invariante

> Sustento uma **invariante de conservação** sob **property-based testing**: em
> 3.800 casos gerados por execução, conciliadas mais revisão mais sem par mais
> rejeitadas igualam a contagem de linhas, e os centavos fecham — verificado por
> **mutação**, não por suíte verde.

| | |
|---|---|
| Números | 1.000 casos por propriedade de conservação; 3.800 gerados por execução da suíte |
| Reproduz | `./gradlew test --tests '*ConservationSpec*'` |
| Keywords | property-based testing, kotest, invariante, mutação |

**Esta é a bullet mais forte do currículo.** É a única que prova um hábito, não
um conhecimento: gerar 1.000 arquivos aleatórios por execução e exigir que o
dinheiro feche.

**"Como você sabe que o teste funciona?"** — a resposta que separa: eu quebrei o
código de propósito. Descartar silenciosamente uma linha sem par reprova as duas
propriedades. Um teste que nunca falhou não provou nada ainda.

**Detalhe que rende conversa:** rejeitada conta na **cardinalidade** e não na
**soma**. As duas metades da invariante medem coisas diferentes de propósito — se
medissem a mesma, uma delas seria decorativa.

---

### B4 — A DSL de layout

> Faço parsing de layouts **FEBRABAN de largura fixa** por uma **DSL
> declarativa** em vez de offsets espalhados pelo código: um layout de banco novo
> são **11 linhas de dado e nenhuma linha de código**, provado por um teste que
> declara um banco com posições e discriminador diferentes sem tocar no parser.

| | |
|---|---|
| Número | 11 linhas — contadas na declaração real em `SecondBankLayoutSpec` |
| Reproduz | `./gradlew test --tests '*SecondBankLayoutSpec*'` |
| Keywords | DSL, Kotlin type-safe builder, FEBRABAN, CNAB, parsing |

**O problema que ela resolve** é reconhecível para quem já viu CNAB:
`substring(37, 52)` espalhado por 400 linhas, e o sexto banco vira arqueologia.

**"Você não está só afirmando que é fácil adicionar um banco?"** — não: há um
teste que **adiciona um**. Banco fictício, posições diferentes, discriminador de
detalhe `"E"` em vez de `"1"`, e o parser não muda.

**A história boa aqui:** essa promessa era **falsa** até pouco tempo. O parser
tinha `spec.equalTo != "1"` chumbado — um banco com outra letra exigiria mudar
código. Só apareceu quando escrevi o teste que exercitava a afirmação. Escrever o
teste da bullet é o que revelou que a bullet mentia.

**Detalhe que impressiona:** o layout falha ao **carregar**, não ao processar.
Campo além do registro, intervalo invertido, posição zero e nome repetido lançam
na construção — layout quebrado derruba o boot, não o arquivo do cliente às três
da manhã.

---

### B5 — Streaming e heap

> Streamo a ingestão por **Kotlin Flow** com backpressure limitado: 100 mil
> linhas a **34 MB de pico de heap** contra **127 MB** lendo o mesmo arquivo em
> memória — e reporto que a **vazão é equivalente** entre as duas, porque foi
> isso que a medição disse.

| | |
|---|---|
| Números | 34 MB vs 127 MB de pico; 25.663 vs 24.306 linhas/s (dentro do ruído) |
| Reproduz | `./gradlew measure` |
| Keywords | Kotlin Flow, coroutines, backpressure, heap, streaming |

**A segunda metade da bullet é o diferencial.** Quase todo candidato diria
"streaming é mais rápido". A medição disse que **não é** — o ganho é memória. Dizer
isso na bullet demonstra que o número veio de medição e não de intuição.

**"Então por que streamar?"** — porque o pico de heap é o que decide se o arquivo
de 2 GB do cliente sobe ou derruba o processo. Em memória o pico cresce com o
arquivo; em streaming, não.

---

### B6 — Disciplina de benchmark

> Reporto vazão em regime após **descartar o warmup da JVM**, mediana de cinco
> execuções, e separo o número **com persistência** (9.405 linhas/s) do número só
> de parsing (25.663), porque os primeiros segundos de uma execução medem o
> compilador e o segundo número descreve metade do trabalho.

| | |
|---|---|
| Números | 5 execuções, 2 descartadas, mediana das 3; 9.405 com banco, 25.663 sem |
| Reproduz | `./gradlew measure` e `./gradlew measurePersisted` |
| Keywords | JMH-style benchmark, JIT warmup, JVM, mediana |

**Separa quem usou a JVM de quem leu sobre ela.** Benchmark de JVM sem warmup
mede o compilador, não o código.

**A segunda metade é mais rara ainda:** a maioria citaria os 25.663 e pararia. O
banco custa **2,7× do tempo total** — quem cita o número sem persistência está
descrevendo a metade do trabalho que não toca disco.

**Se pedirem uma história:** a primeira versão da medição com banco reusava o
mesmo digest, e a idempotência devolvia o lote pronto sem gravar nada. O
resultado teria sido espetacular e não teria medido nada. Hoje cada execução gera
digest próprio e há um `check` de que as 100 mil linhas foram mesmo gravadas.

---

### B7 — Otimização medida

> Cortei a conciliação de **10.928 para 25.663 linhas/s** trocando varredura
> linear por índice de título, com a **equivalência do razão provada** contra uma
> implementação ingênua sobre 800 arquivos gerados — otimização que muda
> resultado é bug.

| | |
|---|---|
| Números | 2,3× de vazão, −51% de heap; 800 arquivos na prova de equivalência |
| Reproduz | `./gradlew measure` antes/depois; `./gradlew test --tests '*ReconciliationSpec*'` |
| Keywords | profiling, complexidade, property-based testing |

**A ordem importa e é o ponto:** o perfil veio primeiro. A conciliação refiltrava
a lista inteira de recebíveis a cada linha — O(linhas × recebíveis) — e isso
dominava o tempo. Nenhum índice de banco foi criado por suposição.

**"Como você garante que a otimização não mudou o resultado?"** — comparando o
razão indexado com o de uma implementação ingênua, lenta e obviamente correta,
sobre 800 arquivos gerados. E conferido por mutação: deixar as duas estruturas
saírem de sincronia reprova.

---

### B8 — Concorrência

> Mantenho o reprocessamento seguro sob concorrência com **digest SHA-256** atrás
> de índice único e **advisory lock** por arquivo, verificado por **8 corrotinas**
> disputando o mesmo arquivo contra **PostgreSQL real** e produzindo um único
> lote.

| | |
|---|---|
| Números | 8 corrotinas, 1 lote, 50 linhas gravadas uma vez |
| Reproduz | `./gradlew integrationTest --tests '*BatchImporterSpec*'` |
| Keywords | advisory lock, SHA-256, idempotência, Testcontainers, corrotinas |

**Cuidado ao citar:** o teste de corrida usa **50 linhas**, não 100 mil. As 100
mil são da medição de vazão, que é outro teste. Misturar os dois é o tipo de erro
que um entrevistador atento pega.

**"Por que advisory lock se você já tem índice único?"** — o índice garante que
não haja duas linhas; o lock evita que dois processos façam o trabalho todo para
um deles descobrir no fim que perdeu. Serializa antes, em vez de colidir depois.

**Detalhe que rende:** a chave do lock sai do **digest**, não de `hashCode()`. O
`hashCode` de String é estável por especificação, mas o de qualquer outro tipo
não é — e um lock cuja chave muda entre versões deixa de ser lock sem avisar.

**E a variante `xact`:** o lock morre com a transação, inclusive se o processo
cair. Lock de sessão que sobrevive ao dono trava a fila até alguém reiniciar o
banco.

---

### B9 — A decisão de i18n

> Mantenho o motor **livre de texto para humano**: ele emite códigos estáveis com
> parâmetros e o console **Rails** os renderiza, então um terceiro idioma não toca
> uma linha de Kotlin — com a paridade provada por um teste que lê o `enum` do
> Kotlin e exige tradução nos dois idiomas.

| | |
|---|---|
| Evidência | Ocorrência é `{line, code, params}`; spec afirma que o código cru **não** aparece na tela |
| Reproduz | `cd console && bin/rspec spec/i18n` |
| Keywords | i18n, Rails, arquitetura de fronteira |

**É o oposto do padrão comum**, onde a mensagem em inglês vaza do backend para a
tela. Três ganhos: o motor fica testável sem string de UI, idioma novo não toca
em Kotlin, e o código vira estável para log e métrica enquanto o texto muda.

**O gate que vale contar:** `i18n-tasks` compara os dois YAML **entre si** — um
código que falte nos **dois** idiomas passa batido. Mas quem define o conjunto é o
`enum` do motor. Então o spec **lê o Kotlin** e exige o par. É por isso que
`OccurrenceCode` é enum e não String livre: conjunto fechado se pode provar.

**A história:** a regra estava pela metade. A tela mostrava *"Linha 6: data
inválida em **paidAt**"* — identificador camelCase no meio de prosa em português,
no ponto exato que o projeto vende como sua decisão mais afiada. Os parâmetros
também precisavam da regra.

**E o efeito de segunda ordem:** com o campo liderando a frase, o português exige
concordância de gênero — "data de pagamento **inválida**" contra "valor
**inválido**". Um template só não concorda com os dois. Em inglês teria servido;
foi o português que forçou a separação. Isso só aparece quando existe um segundo
idioma de verdade, não a promessa de que caberia.

---

### B10 — Locale do arquivo vs locale da interface

> Entrego o console em **inglês e português** com completude de tradução **barrada
> no CI**, e faço o parsing decimal do arquivo a partir do **layout** e não do
> locale da interface, para o mesmo arquivo importar idêntico para os dois
> operadores.

| | |
|---|---|
| Evidência | Spec roda o mesmo parsing sob `Locale.US`, `pt-BR` e `GERMANY` |
| Reproduz | `./gradlew test --tests '*LocaleIndependent*'` e `i18n-tasks missing` |
| Keywords | i18n, locale, CI gate, parsing |

**O bug que ela evita é concreto:** um analista com a interface em inglês
importando CSV brasileiro precisa que `1.234,56` vire 123456 centavos. Se o
parsing herdasse o locale da UI, **o mesmo arquivo importaria diferente para dois
usuários** — e um deles receberia dinheiro no lugar errado.

---

### B11 — A fila humana

> Encaminho o que o casador **não decide** para uma fila humana carregando o
> conjunto de candidatos e o motivo em vez de adivinhar, e a decisão **nunca
> remove a linha do razão** — a conservação é medida contra o arquivo, não contra
> si mesma.

| | |
|---|---|
| Evidência | `ReviewSpec` compara cardinalidade e soma antes/depois da decisão |
| Reproduz | `./gradlew integrationTest --tests '*ReviewSpec*'` |
| Keywords | domain modeling, human-in-the-loop |

**A regra que sustenta:** casar por valor é palpite, e palpite em conciliação põe
dinheiro na conta errada em silêncio. Mesmo com **um único** candidato, se o
título não confere, vai para a fila.

**"Isso não derruba sua taxa de casamento automático?"** — derruba, e é o
objetivo. Um número de casamento inflado por chute não é um número melhor.

**A segunda metade é a mais forte:** "nenhum destes" leva a linha para
`UNMATCHED`, nunca para fora. Se o operador pudesse apagar, o razão passaria a
fechar **contra si mesmo** em vez de contra o que o banco mandou.

**Concorrência aqui também:** decidir duas vezes a mesma linha é 409, com a
condição no próprio `UPDATE` em vez de ler-e-então-escrever. Duas abas na mesma
linha é o caso comum, não o exótico.

---

### B12 — A interface

> Construí a tabela de conciliação **para varredura e não para cards**: estado por
> **glifo e filete além de cor**, algarismos tabulares, **atalhos de teclado** e
> layout por prioridade de coluna verificado a **320px** com `scrollWidth` medido.

| | |
|---|---|
| Evidência | 19 verificações em Chromium real (`bin/keyboard-check.rb`, `bin/drawer-check.rb`) |
| Reproduz | `cd console && bin/keyboard-check.rb` |
| Keywords | acessibilidade, responsivo, Hotwire, Stimulus, Turbo |

**Cada escolha tem razão funcional, e é isso que a torna defensável:**

- **Glifo além de cor** — funciona em daltonismo, impressão preto e branco e
  captura de tela sem cor, que é como isso chega ao chat de suporte.
- **Algarismos tabulares** — dígito de largura fixa deixa o olho descer em linha
  reta procurando a linha que não fecha.
- **Tabela não vira card empilhado** — card destrói a varredura vertical de
  valores, que é a única coisa que a tela faz bem.
- **Listra greenbar** — existe há 60 anos porque olho humano perde a linha ao
  varrer coluna longa. Referência funcional, não nostalgia.

**"Vocês testaram acessibilidade?"** — o foco vai na **linha**, não num link
dentro dela; focar o link só funcionava nas linhas com botão, e nas outras o foco
ficava no `body`, deixando o leitor de tela para trás. E cada atalho dispara um
link que **já existe** na página: atalho é caminho mais curto, nunca o único.

---

### B13 — A fronteira arquitetural

> Mantive `domain` **sem nenhum import de framework**, verificado por **teste de
> arquitetura** que quebra o build — é o que faz a suíte de propriedades rodar em
> milissegundos, sem container no caminho.

| | |
|---|---|
| Números | 400 linhas em `domain/`, 96,3% de cobertura, piso de 80% no CI |
| Reproduz | `./gradlew test --tests '*ArchitectureSpec*'` e `./gradlew koverVerify` |
| Keywords | Konsist, arquitetura, cobertura, clean boundaries |

**A justificativa é performance de teste, não pureza.** Sem framework no caminho,
3.800 casos gerados rodam em segundos — e uma suíte que roda em segundos é uma
suíte que você roda.

**Detalhe honesto que rende ponto:** enquanto Spring não estava no classpath, o
teste passava **por tabela** — o import falharia na compilação de qualquer forma.
Ele só virou rede de verdade quando a API entrou. Por isso a spec tem um segundo
exemplo que falha se o escopo varrido ficar vazio: teste de arquitetura que não
varre nada passa sempre.

---

## 3. Perguntas de entrevista que este projeto convida

**"Qual foi a decisão mais difícil?"**
Separar o que o motor decide do que ele recusa decidir. É tentador fazer o motor
"resolver" tudo — e um sistema de dinheiro que adivinha é pior que um que
pergunta.

**"O que você faria diferente?"**
Teria medido antes de otimizar a estrutura de dados — e foi o que acabei
fazendo, mas só depois de a medição mostrar vazão idêntica onde eu esperava
diferença. E teria rodado o CI mais cedo: ele passou a maior parte do projeto num
diretório onde o GitHub não lê, ou seja, existia e não podia rodar.

**"Qual bug te ensinou mais?"**
O HMAC recusando toda chamada **filtrada**. O cliente assinava o caminho com a
query string; `HttpServletRequest.requestURI` não inclui a query. Toda chamada
com `?status=` dava 401 — e nenhuma suíte via, porque só as chamadas sem filtro
eram assinadas de verdade nos testes. O teste entrava pela porta que o código
abriu para ele.

**"Como você garante que os testes valem alguma coisa?"**
Quebrando o código de propósito. Cada gate deste projeto foi conferido assim:
remover o advisory lock reprova o teste de corrida; descartar uma linha sem par
reprova a conservação; um código novo no enum sem tradução reprova o gate de
i18n. Teste que nunca falhou não provou nada ainda.

**"O que não está pronto?"**
CNAB 240 e OFX — dois formatos já provam a DSL, e cortar formato antes de cortar
i18n e medição foi decisão consciente. E três coisas não medidas: plano de
consulta, taxa de casamento sobre corpus real e vazão sob contenção.

---

## 4. Variantes por vaga

**Vaga Kotlin/JVM backend** — abra com B2 (value class e overflow), B3
(invariante), B5+B6 (Flow e benchmark), B8 (concorrência). Corte B12.

**Vaga Rails/full-stack** — abra com B9 (fronteira de i18n) e B10, depois B12
(interface e acessibilidade), B11 (fila humana). Mantenha B1 pelo Kotlin, que é
o diferencial contra outros candidatos Rails.

**Vaga de fintech / meios de pagamento** — B2, B3, B8, B11, nessa ordem. Todas
falam de dinheiro não sumindo, que é o vocabulário da casa.

**Vaga sênior / com peso de arquitetura** — B13 (fronteira), B4 (DSL), B7
(otimização medida), B6 (disciplina de medição). São as que mostram julgamento,
não domínio de ferramenta.

---

## 5. O que NÃO afirmar

Escrito porque a bullet que não se sustenta custa mais que a bullet que falta.

| Não diga | Porque |
|---|---|
| "Suporte a cinco bancos" | Existe **um** layout CNAB 400 sintético e um CSV. O que se demonstra é o custo de adicionar o próximo: 11 linhas. |
| "Importa a 25 mil linhas/s" | Sem qualificar, sugere ponta a ponta. Com o `INSERT` são 9.405. |
| "Streaming é mais rápido" | A medição disse que a vazão é equivalente. O ganho é heap: 34 MB contra 127 MB. |
| "8 corrotinas gravando 100 mil linhas" | O teste de corrida usa 50 linhas. As 100 mil são da medição de vazão. |
| "Processa CNAB 240 e OFX" | Não existem. Foram cortados, e o corte está documentado. |
| "X% de casamento automático" | Não medido, e medir contra corpus que você mesmo gerou mede o gerador. |
| "Cobertura de N%" sem contexto | 96,3% é de `domain/` apenas, com piso de 80% no CI. Não há piso global de propósito. |

---

## 6. Números de referência

| Métrica | Valor | Como reproduzir |
|---|---:|---|
| Kotlin de produção | 2.522 linhas / 29 arquivos | `find engine/src/main -name '*.kt' \| xargs cat \| wc -l` |
| `domain/` sem framework | 400 linhas | idem, em `domain/` |
| Cobertura de `domain/` | 96,3% de linhas | `./gradlew koverVerify` |
| Exemplos no motor | 99 rápidos + 32 integração | `./gradlew test integrationTest` |
| Exemplos no console | 68 | `cd console && bin/rspec` |
| Verificações em navegador | 19 | `bin/keyboard-check.rb`, `bin/drawer-check.rb` |
| Casos gerados por execução | 3.800 | contagem dos `checkAll(n)` |
| Vazão — só parsing | 25.663 linhas/s | `./gradlew measure` |
| Vazão — com `INSERT` | 9.405 linhas/s | `./gradlew measurePersisted` |
| Pico de heap — streaming | 34 MB | `./gradlew measure` |
| Pico de heap — em memória | 127 MB | idem |
| Ganho do índice de título | 2,3× | medição antes/depois registrada |
| Layout de banco novo | 11 linhas | declaração em `SecondBankLayoutSpec` |
| Decisões documentadas | 40 | `grep -c '^## ' docs/DECISIONS.md` |
| Migrations | 2 | `ls engine/src/main/resources/db/migration` |

Máquina das medições: AMD Ryzen 7 5800XT (16 threads), 15 GB RAM, WSL2, OpenJDK
21.0.12, PostgreSQL 16 em container. Protocolo: 5 execuções, 2 descartadas por
warmup de JIT, mediana das 3, heap fixo em `-Xms64m -Xmx1g`.
