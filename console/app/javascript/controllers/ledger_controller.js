import { Controller } from "@hotwired/stimulus"

/**
 * Navegacao por teclado no razao.
 *
 * Ferramenta de uso diario sem atalho e ferramenta que respeita pouco quem usa:
 * o analista varre centenas de linhas todo dia, e tirar a mao do teclado a cada
 * linha custa mais que o resto da tela junto.
 *
 *   j / k ou setas   linha seguinte / anterior
 *   Enter ou r       abre a revisao da linha (so faz sentido em NEEDS_REVIEW)
 *   g / G            primeira / ultima linha
 *   ?                mostra e esconde a lista de atalhos
 *
 * O que este controller NAO faz: nada que so exista aqui. Cada atalho leva a um
 * link que ja esta na pagina -- quem navega com o mouse chega no mesmo lugar, e
 * quem usa leitor de tela tambem. Atalho e caminho mais curto, nunca o unico.
 */
export default class extends Controller {
  static targets = ["row", "help"]

  connect() {
    this.index = -1
    this.onKey = this.onKey.bind(this)
    document.addEventListener("keydown", this.onKey)
  }

  disconnect() {
    document.removeEventListener("keydown", this.onKey)
  }

  onKey(event) {
    // Nunca sequestrar tecla dentro de campo: quem esta digitando "j" num filtro
    // quer a letra, nao navegar. Modificador tambem sai fora -- Ctrl+R e do
    // navegador, e roubar isso e uma hostilidade.
    if (this.isTyping(event.target)) return
    if (event.metaKey || event.ctrlKey || event.altKey) return

    const actions = {
      j: () => this.move(1),
      ArrowDown: () => this.move(1),
      k: () => this.move(-1),
      ArrowUp: () => this.move(-1),
      g: () => this.jumpTo(0),
      G: () => this.jumpTo(this.rowTargets.length - 1),
      r: () => this.open(),
      Enter: () => this.open(),
      "?": () => this.toggleHelp(),
      Escape: () => this.hideHelp()
    }

    const action = actions[event.key]
    if (!action) return

    event.preventDefault()
    action()
  }

  isTyping(element) {
    if (!element) return false
    if (element.isContentEditable) return true
    return ["INPUT", "TEXTAREA", "SELECT"].includes(element.tagName)
  }

  move(step) {
    if (this.rowTargets.length === 0) return
    const next = this.index < 0 ? (step > 0 ? 0 : this.rowTargets.length - 1) : this.index + step
    this.jumpTo(Math.max(0, Math.min(next, this.rowTargets.length - 1)))
  }

  jumpTo(index) {
    if (this.rowTargets.length === 0) return

    this.index = index
    const row = this.rowTargets[index]

    this.rowTargets.forEach((r) => {
      r.removeAttribute("data-current")
      r.tabIndex = -1
    })
    row.setAttribute("data-current", "true")
    // `nearest` e nao `center`: rolar a pagina inteira a cada tecla desorienta
    // mais do que ajuda quando a linha ja esta visivel.
    row.scrollIntoView({ block: "nearest" })

    // O foco vai na PROPRIA LINHA, nao num link dentro dela.
    //
    // Focar o link so funcionava nas linhas que tem botao de revisao -- nas
    // outras o foco ficava no body e o leitor de tela nao acompanhava o cursor
    // visual. Com tabIndex = -1 a linha e focavel por codigo sem entrar na
    // ordem de tabulacao, e o `aria-selected` diz ao leitor o que mudou.
    row.tabIndex = 0
    row.focus({ preventScroll: true })
  }

  open() {
    if (this.index < 0) return
    // So linha em revisao tem para onde ir. Nas demais a tecla nao faz nada, em
    // vez de navegar para algum lugar arbitrario.
    const link = this.rowTargets[this.index]?.querySelector("a[data-review]")
    if (link) link.click()
  }

  toggleHelp() {
    if (!this.hasHelpTarget) return
    this.helpTarget.hidden = !this.helpTarget.hidden
  }

  hideHelp() {
    if (this.hasHelpTarget) this.helpTarget.hidden = true
  }
}
