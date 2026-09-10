# IBM Plex — vendorada, nao via CDN

Arquivos `woff2` do IBM Plex Sans e Mono, sob SIL Open Font License 1.1
(`IBM-Plex-OFL.txt`). Subconjunto latino, do pacote `@fontsource`.

**Por que local e nao CDN:** dependencia de CDN e uma requisicao a terceiro em
toda pagina, um ponto de falha fora do seu controle e um vazamento de IP do
operador para quem hospeda a fonte. O peso e 100 KB no total, servidos pelo
mesmo processo que ja serve o resto.

**Por que estas cinco:** Sans 400/500/600 e Mono 400/500 -- exatamente os pesos
que a escala tipografica usa. Peso que ninguem usa e byte que todo mundo baixa.
