import { Controller } from "@hotwired/stimulus"

/**
 * Liga e desliga o drawer de revisao conforme a largura da tela.
 *
 * Esconder o frame com CSS NAO impede o Turbo de navegar dentro dele: em tela
 * estreita o operador clicava em "Revisar", o frame carregava atras de um
 * `display: none` e a tela nao mudava. Nada acontecia, sem erro nenhum -- o
 * pior modo de falha de interface.
 *
 * Entao o alvo do link e decidido aqui: existe drawer -> o link mira o frame;
 * nao existe -> `_top`, e o navegador vai para a pagina inteira, que ja existe.
 * O breakpoint fica no CSS (`matchMedia`), nao repetido em numero magico.
 */
export default class extends Controller {
  static targets = ["link", "frame"]
  static values = { breakpoint: { type: String, default: "(min-width: 64rem)" } }

  connect() {
    this.query = window.matchMedia(this.breakpointValue)
    this.apply = this.apply.bind(this)
    this.query.addEventListener("change", this.apply)
    this.apply()
  }

  disconnect() {
    this.query.removeEventListener("change", this.apply)
  }

  apply() {
    const target = this.query.matches ? "review-drawer" : "_top"
    this.linkTargets.forEach((link) => link.setAttribute("data-turbo-frame", target))

    // Ao encolher para menos que o breakpoint, o conteudo que ficou no drawer
    // e descartado: deixa-lo la esconde estado que o usuario nao consegue ver
    // nem fechar.
    if (!this.query.matches && this.hasFrameTarget) this.frameTarget.innerHTML = ""
  }
}
