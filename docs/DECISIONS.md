# Decisões

Uma decisão por seção: o que foi decidido, contra o quê, e o que teria que
mudar para a decisão mudar. Decisão sem alternativa descartada não é decisão,
é o default.

O que **não** está aqui está em [ESCOPO](#escopo-o-que-ficou-de-fora).

---

## 1. Dinheiro é inteiro de centavos em `value class`

**Decisão.** `Cents(val value: Long)` como `@JvmInline value class`.

**Contra.** `Double` (descartado: 0,1 + 0,2 não é 0,3, e o razão fecha em
centavo), `BigDecimal` solto (descartado: correto na aritmética, mas aloca por
operação e não impede somar reais com dólares porque todo valor é o mesmo tipo)
e um `data class` comum (descartado: mesma segurança de tipo, mas com alocação —
`value class` some em runtime e vira um `long`).

**Consequência.** Formatação para humano acontece só na borda, no Rails. O motor
nunca produz `"R$ 1.234,56"`.

**Mudaria se.** O sistema precisasse de mais de uma moeda ao mesmo tempo — aí
`Cents` ganha um campo `Currency` e deixa de ser `value class` de um campo só.

## 2. Overflow estoura em vez de dar a volta

**Decisão.** `plus`, `minus` e `abs` usam `Math.addExact`, `subtractExact` e
`absExact`.

**Por quê.** Wraparound em `Long` transforma um total gigante em número
**negativo**, e a invariante de conservação fecharia em cima de um valor errado
— o único modo de falha que este projeto existe para impedir. `kotlin.math.abs`
tem a mesma armadilha: `abs(Long.MIN_VALUE)` devolve `Long.MIN_VALUE`, que é
negativo.

**Consequência.** Um arquivo cujos valores somem além de `Long.MAX_VALUE` falha
o lote de forma explícita e auditável, em vez de importar um total errado.

## 3. `domain` não importa framework, e isso é um teste

**Decisão.** Nada de Spring, jOOQ, Jackson, Flyway, `jakarta.*` ou corrotinas
dentro de `domain`. Verificado por `ArchitectureSpec` com Konsist.

**Contra.** Um documento dizendo a mesma coisa (descartado: não quebra o build
quando alguém escreve `@Autowired` no `Reconciler`) e um módulo Gradle separado
(descartado por ora: resolve por construção, mas troca um teste de 20 linhas por
uma reorganização do build — a trocar quando o motor tiver um segundo consumidor
do domínio).

**Consequência.** As 3.000 execuções de property testing rodam em milissegundos
porque não há container no caminho. É a diferença entre rodar a suíte a cada
salvamento e parar de rodar.

**Cuidado que o teste esconde.** Enquanto Spring não estiver no classpath do
módulo, um `import org.springframework.*` em `domain` já falha na compilação, e
o teste de arquitetura passa por tabela. Ele só vira a única rede quando a API
entrar. Por isso a spec tem um segundo exemplo que falha se o escopo varrido
ficar vazio: teste de arquitetura que não varre nada passa sempre.

## 4. Tolerância é política injetada, com duas dimensões

**Decisão.** `MatchingPolicy(amountTolerance: Cents, dateToleranceDays: Long)`,
passada para o matcher. `STRICT` é centavo e dia exatos; `DEFAULT` é R$ 2,00 e
três dias.

**Por quê duas dimensões.** Valor e data divergem por motivos diferentes e
independentes: tarifa bancária mexe no valor, prazo de compensação mexe na data.
Uma tolerância só obrigaria a escolher qual erro tolerar.

**Contra.** Constante no matcher (descartado: número de política escondido em
código de domínio, que ninguém acha quando o banco muda a tarifa).

**Aberto no pedido.** O brief pede "casamento por tolerância" sem fixar dimensão
nem limite. Os valores de `DEFAULT` são escolha desta implementação, não
requisito confirmado.

## 5. O motor só casa sozinho quando o título identifica o lançamento

**Decisão.** Casamento automático exige o mesmo *nosso número*. Bater apenas
valor e data manda para a fila humana (`AMOUNT_ONLY`), mesmo com um único
candidato. Dois candidatos equivalentes nunca elegem vencedor (`AMBIGUOUS`).

**Por quê.** Casar por valor é palpite, e palpite em conciliação põe dinheiro na
conta errada de forma silenciosa. A fila humana carrega o conjunto de candidatos
e o motivo — o operador decide com a mesma informação que o motor teve.

**Consequência.** A taxa de casamento automático fica menor do que ficaria com
um desempate heurístico. É o objetivo: um número de casamento automático inflado
por chute não é um número melhor.

## 6. Um recebível casa no máximo uma vez

**Decisão.** `Reconciler` consome o recebível ao casar; ele sai do conjunto de
candidatos das linhas seguintes.

**Consequência.** O resultado depende da ordem das **linhas** — que é a ordem
física do arquivo, a única que o operador consegue auditar contra o papel. Já a
ordem dos **recebíveis** não pode importar, e não importa: o desempate é estável
por id em `Matcher.stable()`, com propriedade que embaralha a lista e compara o
razão inteiro.

## 7. Linha inválida é `ParsedLine` selado, não `Entry` com campo nulo

**Decisão.** O parser devolve `ParsedLine.Valid(Entry)` ou
`ParsedLine.Rejected(Occurrence)`.

**Contra.** `Entry` com `amount: Cents?` (descartado: campo nulo é o caminho
curto para "desconhecido virou zero", e `cents ?: 0` é exatamente como o dinheiro
some sem ninguém ver).

**Consequência.** Rejeitada conta na cardinalidade e **não** conta na soma. As
duas metades da invariante da seção 5.3 do brief medem coisas diferentes de
propósito.

## 8. Código de ocorrência é `enum`, não `String`

**Decisão.** `OccurrenceCode` é enum; a API serializa o nome.

**Por quê.** O conjunto fechado deixa o gate de i18n **provar** que todo código
tem tradução nos dois idiomas — com `String` livre, a checagem vira torcida. E o
código continua estável para log e métrica enquanto o texto muda.

## 9. O motor não emite texto para humano

**Decisão.** Ocorrência é `{line, code, params}`. O texto mora no Rails.

**Consequência.** Adicionar um terceiro idioma não toca em Kotlin. É o oposto do
padrão comum, onde a mensagem em inglês vaza do backend para a tela.

## 10. Separador decimal é propriedade do layout, nunca do locale

**Decisão.** `FixedDecimal`, `BrazilianDecimal` e `PlainDecimal` recebem os
separadores do layout. Nenhum caminho de parsing lê `Locale.getDefault()`.

**Por quê.** Um analista com a interface em inglês importando CSV brasileiro
precisa que `1.234,56` vire 123456 centavos. Se o parsing herdasse o locale da
UI, o **mesmo arquivo importaria diferente para dois usuários** — e um deles
receberia dinheiro no lugar errado.

**Travado por.** Spec que roda o mesmo parsing sob `Locale.US`, `pt-BR` e
`GERMANY` e exige o mesmo resultado.

## 11. Casa decimal extra é rejeição, não arredondamento

**Decisão.** `FixedDecimal(places = 3)` lendo `0001234561` devolve
`ROW_INVALID_AMOUNT`, não `Cents(123456)`.

**Por quê.** Truncar um décimo de centavo é perda silenciosa. Em dinheiro, o
comportamento correto para "não cabe" é recusar, não aproximar.

## 12. `BigInteger` no caminho de leitura

**Decisão.** O parser converte dígitos com `BigInteger` e só então checa se cabe
em `Long`.

**Por quê.** `"9999999999999999999999".toLong()` lança, mas um dígito a mais num
campo de tamanho fixo não deveria derrubar o lote inteiro — vira
`ROW_AMOUNT_OUT_OF_RANGE` naquela linha. `BigInteger` é o único jeito de decidir
"não cabe" sem já ter perdido a informação.

## 13. Layout é dado e falha ao carregar

**Decisão.** A DSL valida na construção: posição zero, intervalo invertido, campo
além do `recordLength` e nome repetido lançam `IllegalArgumentException`.

**Por quê.** Layout quebrado tem que falhar ao subir a aplicação, não às três da
manhã no meio do arquivo do cliente.

**Posições são 1-based e inclusivas** — como a especificação FEBRABAN, para o
layout ser conferido lado a lado com o manual do banco sem aritmética.

**Divergência do brief.** O exemplo do brief usa `as = Text`; `as` é palavra
reservada em Kotlin e só funcionaria com crase. O parâmetro é posicional:
`field("amount", 127..139, FixedDecimal(places = 2))`. E `bankCode` saiu de
`1..3` para `2..4`, porque `1..1` é o tipo de registro.

## 14. O layout CNAB 400 aqui é sintético

**Decisão.** Existe **um** layout, `cnab400-sintetico`, e o gerador que produz os
arquivos que o exercitam está versionado no repositório.

**Por quê.** Não há neste repositório especificação FEBRABAN nem manual de banco
nenhum. Afirmar "suporte a cinco bancos" seria afirmar o que não foi verificado.
O que se demonstra é o **custo de acrescentar o próximo**, que é dado, não
código.

**Nenhum dado real de cliente, nem anonimizado**, em nenhuma fixture.

## 15. CSV usa parser de CSV de verdade

**Decisão.** `commons-csv`, não `split(delimiter)`.

**Por quê.** Delimitador dentro de aspas, quebra de linha embutida no campo e
aspas escapadas — `split` erra os três, e erra em silêncio, produzindo colunas
deslocadas em vez de erro.

**Consequência assumida.** Com quebra de linha dentro de aspas, o `line` da
ocorrência é o **ordinal do registro** (+1 pelo cabeçalho), não o número físico
da linha do arquivo. Para largura fixa os dois coincidem sempre.

## 16. Coluna obrigatória ausente falha o arquivo, não a linha

**Decisão.** CSV sem uma coluna obrigatória lança na leitura do cabeçalho.

**Por quê.** Rejeitar linha a linha produziria N ocorrências idênticas e
esconderia o fato relevante: o operador mandou o arquivo errado. Um erro no lugar
certo vale mais que mil no lugar errado.

## 17. Header e trailer são contabilizados, não descartados

**Decisão.** `ParsedFile.structuralLines` guarda o número das linhas estruturais;
`accountedLines` é `lines.size + structuralLines.size`.

**Por quê.** A promessa é que toda linha do arquivo tem destino registrado. "Eu
ignorei porque é header" é um destino legítimo — mas precisa aparecer, senão
"ignorei o header" e "perdi uma linha" ficam indistinguíveis.

## 18. Piso de cobertura só em `domain`

**Decisão.** Kover exige 80% em `dev.wasdevv.tally.domain.*` e nada nos demais
pacotes.

**Por quê.** Cobertura por si só não diz nada; a invariante diz. O piso existe
onde uma queda de cobertura significa regra de negócio sem teste. Piso global
viraria teste escrito para o número.

**Medido.** 96,3% de linhas em `domain` hoje, e o piso falha quando levantado
para 100 — ou seja, o gate está ligado de verdade.

## 19. detekt tem config versionada, com motivo escrito

**Decisão.** `config/detekt/detekt.yml` desliga `MagicNumber` **apenas** em
`parsing/cnab` e `parsing/layout`, e afrouxa `ReturnCount` para guard clauses.

**Por quê.** Num layout de largura fixa a posição **é** o dado: `field("amount",
127..139)` se confere contra o manual do banco. Extrair `AMOUNT_START = 127`
devolveria exatamente os offsets espalhados que a DSL existe para eliminar. Em
`parsing/types` a regra continua valendo, e por isso `100`, `2000` e `6` viraram
`CENTS_PER_UNIT`, `CENTURY` e `YYMMDD_LENGTH`.

**Regra de crescimento.** Todo desvio novo entra com o motivo na mesma linha.
Gate que se afrouxa sem justificativa escrita deixa de ser gate.

## 20. A importação inteira em uma transação, sem estado `PROCESSING`

**Decisão.** As linhas são gravadas antes da linha do lote (FK deferida), e o
lote só nasce no fim, com a contagem já conferida contra o banco.

**Contra.** Criar o lote como `PROCESSING` e atualizar no fim (descartado: exige
recuperação de lote travado, e um crash no momento errado deixa um lote que
*parece* válido).

**Consequência.** Falha no meio não deixa lote concluído porque não deixa lote
nenhum. "Importou pela metade" não é um estado observável.

**Mudaria se.** Um arquivo passasse a não caber em uma transação — aí entra
lote `PROCESSING` com retomada, e o custo de recuperação passa a se pagar.

## 21. Advisory lock derivado do digest, na transação

**Decisão.** `pg_advisory_xact_lock` com chave derivada dos primeiros 8 bytes do
SHA-256, adquirido dentro da transação que faz o trabalho.

**Por quê a variante `xact`.** O lock morre com a transação, inclusive se o
processo cair. Lock de sessão que sobrevive ao dono trava a fila de importação
até alguém reiniciar o banco.

**Por quê o digest e não `hashCode()`.** `hashCode` de String é estável por
especificação, mas o de qualquer outro tipo não é — e um lock cuja chave muda
entre versões deixa de ser lock sem avisar.

**Provado por.** 8 corrotinas na mesma carga: um único lote, 50 linhas, uma vez.
Conferido por mutação — remover o lock reprova o teste.

## 22. Idempotência tem escopo `(lote, linha)`, nunca conteúdo

**Decisão.** A chave única é `(batch_id, line)`. O arquivo inteiro é
desduplicado pelo digest; as linhas dentro dele, não.

**Por quê.** Duas linhas idênticas no mesmo arquivo são **dois pagamentos
legítimos** — mesmo sacado, mesmo valor, mesmo dia acontece. Desduplicar por
conteúdo apagaria dinheiro de verdade e a conservação fecharia em cima da perda.

## 23. jOOQ sem geração de código, com um teste no lugar dela

**Decisão.** `Tables.kt` declara colunas à mão; `SchemaDriftSpec` roda contra o
banco migrado e falha se qualquer campo declarado não existir lá.

**Contra.** `jooq-codegen-gradle` (descartado por ora: exige um Postgres de pé
para *compilar*, e o que ele daria a mais — garantia de que os nomes existem —
custa 40 linhas de teste).

**`ponytail:`** sem codegen. Migrar quando o esquema passar de ~10 tabelas ou
quando manter isto sincronizado começar a doer.

## 24. O HMAC assina o digest do conteúdo, não os bytes

**Decisão.** O material assinado é `timestamp \n método \n caminho \n digest`.
Em requisição JSON o filtro calcula o digest dos bytes crus; em upload
multipart, o cliente declara o digest em header e o controller o confere contra
os bytes recebidos.

**Por quê não assinar os bytes direto.** Em multipart o container precisa do
stream intacto para montar as partes. Drenar o corpo no filtro para assinar
deixava o upload chegar **vazio** ao controller — sem erro visível. Foi
encontrado rodando, não pensando.

**A corrente fecha em dois pontos**, e é isso que amarra o arquivo à assinatura:
o filtro exige o header, o controller confere o digest contra o conteúdo. Trocar
o arquivo depois de assinar dá 400, e há spec.

**O que isto não cobre.** Nome do arquivo e demais campos do multipart ficam
fora da assinatura. Para este sistema o conteúdo é o que importa; se um campo de
formulário passar a ter efeito colateral, ele precisa entrar no material
assinado.

## 25. Índice por nosso número na conciliação — medido, não suposto

**Decisão.** `Reconciliation` indexa os recebíveis por nosso número em vez de
refiltrar a lista a cada linha.

**Por quê.** A medição mostrou vazão idêntica entre streaming e leitura total,
e abaixo do esperado. A causa não era o parser: a conciliação era O(linhas ×
recebíveis). Depois do índice, 2,3× — números antes e depois em
`docs/MEASUREMENTS.md`.

**Como a semântica ficou provada.** Otimização que muda resultado é bug.
`ReconciliationSpec` compara o razão indexado com o de uma implementação ingênua
de varredura total, sobre 800 arquivos gerados. Conferido por mutação: deixar o
índice sair de sincronia reprova a suíte.

**Este é o único ponto do projeto onde desempenho ditou desenho** — e só depois
de a medição existir. Nenhum índice de banco foi criado por suposição.

## 26. O console não tem banco

**Decisão.** Rails sem ActiveRecord. Sessão em cookie assinado, e nada mais.

**Por quê.** O razão mora no motor. Espelhar tabela do motor no console, ou
apontar os dois para o mesmo banco, é o atalho que vira dívida no dia em que o
esquema muda e a tela quebra sem ninguém tê-la tocado.

**Consequência.** O console tem uma única porta para os dados — `EngineClient` —
e ela é testada contra um servidor HTTP de verdade, porque o que precisa ser
provado são os bytes na rede.

## 27. O gate de i18n forte lê o enum do motor

**Decisão.** Além de `i18n-tasks missing`/`unused`, um spec lê `OccurrenceCode`
no fonte Kotlin e exige o par nos dois idiomas.

**Por quê.** `i18n-tasks` compara os dois YAML **entre si**: um código que falte
nos dois idiomas passa. Mas quem define o conjunto é o enum do motor, e um
código novo sem tradução nenhuma chega à tela como identificador em maiúsculas
para o operador ler.

**É o retorno do investimento da decisão 8.** `OccurrenceCode` ser enum e não
String livre é o que torna o conjunto fechado — e conjunto fechado se pode
provar. Conferido por mutação: acrescentar um código sem tradução reprova.

## 28. Decisão de revisão nunca remove a linha do razão

**Decisão.** "Nenhum destes" leva a linha para `UNMATCHED`, não para fora. Não
existe descartar.

**Por quê.** A conservação é medida contra o **arquivo**: se o operador pudesse
apagar uma linha, o razão passaria a fechar contra si mesmo em vez de contra o
que o banco mandou — que é exatamente a mentira que este projeto existe para
impedir.

**Provado por.** `ReviewSpec` compara cardinalidade e soma antes e depois da
decisão: as duas ficam iguais. Uma decisão muda o **destino** de uma linha,
nunca o total.

**O motivo original é preservado.** `match_reason` continua dizendo `AMBIGUOUS`
depois da decisão; `decided_reason` diz o que o humano fez. Sobrescrever o
primeiro apagaria por que aquilo chegou à fila.

## 29. Decidir duas vezes é 409, e a condição está no `UPDATE`

**Decisão.** A transição só vale a partir de `NEEDS_REVIEW`, e isso é uma
cláusula do próprio `UPDATE` — não uma leitura seguida de escrita.

**Por quê.** Duas abas do navegador na mesma linha é o caso comum, não o
exótico. Ler-e-então-escrever tem uma janela entre as duas; a condição no
`UPDATE` não tem. A segunda aba afeta zero linhas e recebe conflito.

**409 e não 400.** A requisição está bem formada; o **estado** mudou debaixo
dela. O código diz qual dos dois aconteceu, e a tela consegue dizer ao operador
"alguém já decidiu isto" em vez de "requisição inválida".

## 30. A query string entra no material assinado

**Decisão.** O HMAC cobre `caminho?query`, não só o caminho.

**Como apareceu.** Não foi previsto: foi um 401 na tela de revisão, rodando o
sistema. O cliente assinava o caminho **com** a query; `HttpServletRequest.
requestURI` **não inclui** a query, então toda chamada filtrada falhava — e
nenhuma suíte via, porque só chamadas sem filtro eram assinadas de verdade.

**Por quê assinar a query e não removê-la da assinatura.** As duas pontas
concordarem já resolveria o 401. Mas `?status=` seleciona o que o operador vê,
e o que é interceptável é modificável — assinar mais da requisição é a escolha
que não precisa ser revisitada.

**Coberto por.** Um exemplo de contrato que faz uma chamada **filtrada** contra
o motor real. A lacuna era exatamente essa: nenhum teste assinado passava query.

## 31. `ReviewRepository` separado, porque o linter estava certo

**Decisão.** Ler candidatos e registrar decisão saíram de `LedgerRepository`.

**Como apareceu.** O detekt reclamou do número de métodos da classe. A resposta
preguiçosa seria subir o limite; a reclamação estava certa — a classe tinha
juntado dois trabalhos, gravar o razão durante a importação e resolver o que
ficou pendente depois.

**Regra que isso ilustra.** Gate que aponta um problema real se atende, não se
afrouxa. O limite continua onde estava.

## 32. Qual registro vira lançamento é dado do layout, não regra do parser

**Decisão.** A DSL tem `detail(...)` para registro que vira lançamento e
`record(...)` para estrutura. O parser pergunta `spec.emitsEntry`.

**Como apareceu.** O parser tinha `if (spec.equalTo != "1") return null` — o
discriminador de detalhe **chumbado em código**. Um banco que usasse outra letra
exigiria mudar o parser, o que contradiz a promessa da própria DSL.

**Provado por.** `SecondBankLayoutSpec` declara um banco fictício com posições
diferentes **e** discriminador `"E"`, em 8 linhas de declaração, e lê o arquivo
dele sem nenhuma mudança em `parsing/`. É a bullet do currículo virando teste.

**Ganho de brinde.** Layout que não declara nenhum `detail` agora falha ao
carregar — antes, importaria todo arquivo "com sucesso" e zero lançamento, que é
o silêncio mais caro que este projeto existe para impedir.

**Efeito colateral que valeu.** Vários exemplos antigos de `LayoutSpec` usavam
`record` com campos e passaram a estourar pelo motivo **errado** — falta de
`detail`, não o offset inválido que diziam medir. Corrigidos e reconferidos por
mutação: com a validação de offset desligada, eles voltam a falhar.

## 33. Portas publicadas são configuráveis, e SSL é desligável por ambiente

**Decisão.** `compose.yml` publica `${TALLY_ENGINE_PORT:-8080}` etc., e
`force_ssl`/`assume_ssl` do console vêm de variável com default **ligado**.

**Como apareceu.** Nos dois casos, subindo a pilha — nunca na suíte.

A porta 8080 estava ocupada por outro serviço da máquina e o `compose up` morria
com "port is already allocated". Porta fixa funciona em máquina limpa, e nenhuma
máquina de dev é limpa.

O SSL foi pior porque falhava **em silêncio parcial**: com `assume_ssl`, o Rails
gera URL `https://`, e o redirect logo após a importação apontava para uma porta
que não fala TLS. `GET` respondia 200 e a suíte passava — o fluxo só morria no
navegador, um passo adiante. Nenhum teste seguia redirect para outro esquema.

**O default continua seguro.** Quem sobe atrás de um proxy com TLS não configura
nada; é a pilha local, que não tem terminador, que desliga explicitamente.

## 34. O nome do campo também é traduzido, não só o código

**Decisão.** O parâmetro `field` da ocorrência passa por `field.*` antes de
entrar na frase. `paidAt` vira "data de pagamento" / "payment date".

**Como apareceu.** Olhando a tela: *"Linha 6: data inválida em **paidAt**
(999999)"*. Identificador camelCase inglês no meio de prosa em português — o
mesmo vazamento que `ROW_INVALID_DATE` na tela seria, e no ponto exato que o
projeto vende como sua decisão mais afiada.

**A regra completa, então.** O motor emite código **e parâmetros**, e nenhum dos
dois é texto para humano. Um parâmetro que é identificador de máquina precisa de
tradução como o código precisa; só `raw` e `line` passam crus, porque são o dado
literal do arquivo — é isso que o operador quer conferir contra o papel.

**Gate.** `engine_codes_spec` agora lê os nomes de campo dos **layouts** (que
são dado) e exige rótulo nos dois idiomas. Conferido por mutação: campo novo no
layout sem tradução reprova. E a spec de vazamento passou a afirmar que
`paidAt` não aparece na página, em nenhum idioma.

**Efeito de segunda ordem: gênero.** Com o campo liderando a frase, o português
precisa concordar — "data de pagamento **inválida**" contra "valor
**inválido**". Um template único não concorda com os dois, mas cada **código**
já sabe de que tipo de campo fala, então cada um carrega a sua flexão. Em
inglês o mesmo template serviria; foi o português que exigiu a separação, e é
exatamente o tipo de coisa que só aparece quando existe um segundo idioma de
verdade em vez de uma promessa de que ele caberia.

## 35. O workflow mora na raiz do repositório, não em `tally/`

**Decisão.** `.github/workflows/tally.yml` na raiz, com `paths: ["tally/**"]`.

**Como apareceu.** O arquivo estava em `tally/.github/workflows/`, onde o GitHub
**não lê**. Três jobs — um deles subindo motor de verdade para o contrato — que
nunca executaram e não executariam.

**É a própria regra deste documento sendo violada.** "Gate que não barra não é
gate" estava escrito aqui enquanto o gate não podia sequer rodar. Configuração
inerte é pior que ausente, porque parece pronta.

**Dois defeitos junto, no job de contrato.** `engine/build/libs/*.jar` casa
**dois** arquivos — `bootJar` também produz `engine-plain.jar` — e `java -jar`
teria falhado com um erro que não parece ter nada a ver com o glob. E o processo
precisava de `disown` para sobreviver ao fim do step: cada step é um shell
próprio.

## 36. Atalhos de teclado levam a links que já existem

**Decisão.** `j`/`k`, `g`/`G`, `Enter`/`r`, `?`. Cada um dispara um link que já
está na página.

**Por quê essa restrição.** Atalho é caminho mais curto, nunca o único: quem usa
mouse chega no mesmo lugar, e quem usa leitor de tela também. O foco vai na
**linha** (`tabIndex = -1` mais `focus()`), não num link dentro dela — focar o
link só funcionava nas linhas com botão de revisão, e nas outras o foco ficava no
`body`, deixando o leitor de tela para trás.

**O que a verificação no navegador pegou** e nenhum spec pegaria: o `?` inerte
porque o painel de ajuda estava **fora** do elemento do controller — alvo fora do
escopo simplesmente não existe para o Stimulus, então `hasHelpTarget` era falso,
a tecla chegava, o handler rodava e nada acontecia.

**`console/bin/keyboard-check.rb`** roda 12 verificações num Chromium de verdade.
Fora do CI, porque exige navegador e a pilha de pé — mas versionado, para não
virar "eu testei na mão uma vez".

## 37. O drawer decide o alvo do link no cliente, porque CSS não basta

**Decisão.** Em ≥1024px a revisão abre num Turbo Frame ao lado da tabela; abaixo
disso o mesmo link navega para a página inteira. Quem troca o alvo é um Stimulus
controller lendo `matchMedia`.

**Por quê não só CSS.** Esconder o frame com `display: none` **não impede** o
Turbo de navegar dentro dele. Em tela estreita o operador clicava em "Revisar", o
frame carregava atrás do `none`, e a tela não mudava — nada acontecia, sem erro
nenhum. É o pior modo de falha de interface, e só apareceu medindo em 375px.

**Uma view só.** `reviews/show` se envolve no mesmo frame, então serve à página
inteira e ao drawer sem duplicar markup — duas cópias divergiriam no primeiro
ajuste.

## 38. A decisão responde em Turbo Stream, e o HTML continua inteiro

**Decisão.** `format.turbo_stream` remove a linha resolvida e troca o sumário;
`format.html` continua redirecionando para a fila.

**Por quê manter os dois.** O Stream é caminho mais curto, não o único: um
analista com JS bloqueado ainda precisa conciliar. Há spec para os dois caminhos.

**O id da linha é `entry-<lote>-<linha>`.** `dom_id` do Rails espera um model
ActiveRecord, e aqui não há um — o razão mora no motor e chega como Hash. O par
(lote, linha) é a chave natural, a mesma que o banco usa como `UNIQUE`.

## 39. O OpenAPI é conferido contra os controllers, não escrito de memória

**Decisão.** `docs/openapi.yml` existe, e `openapi_spec.rb` compara as rotas
documentadas com as que os controllers realmente expõem.

**Por quê o teste.** Contrato que envelhece em silêncio não é contrato, é
documentação errada — pior que nenhuma, porque alguém confia nela. Conferido por
mutação: rota nova no controller sem entrada no documento reprova.

**Divergência registrada.** O plano falava em "seis endpoints REST". São
**sete** operações. O número está no teste, não na prosa.

---

## Escopo: o que ficou de fora

Nesta ordem, e por escrito. Adicione quando doer, não quando parecer que vai
doer.

| Fora | Por quê |
|---|---|
| Kafka / fila distribuída | Um `Flow` e uma tabela resolvem. Fila entra quando existir um segundo consumidor. |
| Microserviços | Dois processos já é o limite. |
| Event sourcing | O razão já é o log. |
| Kubernetes | `docker compose up`. |
| GraphQL | Poucos endpoints REST. |
| Hexagonal com 40 interfaces | Uma fronteira de pacote, provada por teste. |
| Multi-tenancy | Um cliente. |
| Auth social, 2FA, RBAC | Um usuário, sessão com cookie assinado. |
| ORM no motor | O SQL é o assunto; ele fica visível. |
