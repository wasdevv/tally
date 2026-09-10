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
