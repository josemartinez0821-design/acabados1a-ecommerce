import { ref } from 'vue'

// Reemplaza el globo nativo del navegador ("Completa este campo") por uno propio con la tipografía
// y colores del sitio. Mismo patrón de estado compartido (módulo, no por componente) que
// useCenterMessage.js/useToast.js. Se activa una sola vez desde App.vue escuchando el evento
// `invalid` en captura sobre document (ver ahí el porqué) - cubre cualquier <input required>/
// type=email/min/etc. de todo el sitio sin tocar formulario por formulario.
const tooltip = ref(null) // { texto, top, left }
let temporizador = null

export function useValidationTooltip() {
  function mostrarTooltip(target, texto) {
    if (temporizador) clearTimeout(temporizador)
    const r = target.getBoundingClientRect()
    const ALTO_ESTIMADO = 44
    const seVaArriba = r.bottom + 8 + ALTO_ESTIMADO > window.innerHeight
    tooltip.value = {
      texto,
      top: seVaArriba ? r.top - ALTO_ESTIMADO - 8 : r.bottom + 8,
      left: Math.min(Math.max(8, r.left), window.innerWidth - 296),
    }
    temporizador = setTimeout(() => { tooltip.value = null }, 4500)
  }
  function ocultarTooltip() {
    if (temporizador) clearTimeout(temporizador)
    tooltip.value = null
  }
  // Para validaciones manuales (no HTML5 required/type) que sí tienen un campo
  // concreto al que apuntar - ej. el selector de tipo de PQRS, o "las contraseñas
  // no coinciden" en Registro - y para el `invalid` nativo (App.vue). Espera a que
  // termine el scroll suave antes de medir la posición del tooltip, si no, apuntaría
  // a donde estaba el campo antes de moverse la pantalla. Un tiempo fijo no sirve:
  // el scroll dura más entre más lejos esté el campo (desde el botón de un formulario
  // largo pasa de medio segundo), así que se revisa cuadro a cuadro hasta que el
  // campo deja de moverse (con tope de ~2 s por si acaso).
  function marcarError(el, texto) {
    if (!el) return
    el.scrollIntoView({ behavior: 'smooth', block: 'center' })
    let ultimoTop = null
    let cuadrosQuieto = 0
    let cuadros = 0
    function revisar() {
      const top = el.getBoundingClientRect().top
      cuadrosQuieto = top === ultimoTop ? cuadrosQuieto + 1 : 0
      ultimoTop = top
      if (cuadrosQuieto >= 4 || ++cuadros > 120) {
        mostrarTooltip(el, texto)
        el.focus({ preventScroll: true })
      } else {
        requestAnimationFrame(revisar)
      }
    }
    requestAnimationFrame(revisar)
  }
  return { tooltip, mostrarTooltip, ocultarTooltip, marcarError }
}
