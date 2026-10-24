<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import NavBar from './components/layout/NavBar.vue'
import FooterSite from './components/layout/FooterSite.vue'
import ToastContainer from './components/layout/ToastContainer.vue'
import SessionWarningModal from './components/layout/SessionWarningModal.vue'
import CenterMessage from './components/layout/CenterMessage.vue'
import ValidationTooltip from './components/layout/ValidationTooltip.vue'
import { useCatalogStore } from './stores/catalog'
import { useAuthStore } from './stores/auth'
import { useCotizacionesStore } from './stores/cotizaciones'
import { useVentasStore } from './stores/ventas'
import { usePqrsStore } from './stores/pqrs'
import { useInactivityLogout } from './composables/useInactivityLogout'
import { useValidationTooltip } from './composables/useValidationTooltip'

const catalog = useCatalogStore()
const auth = useAuthStore()
const cotizStore = useCotizacionesStore()
const ventasStore = useVentasStore()
const pqrsStore = usePqrsStore()
const route = useRoute()

// Sondeo cada 60s para que el cliente vea el aviso de "cotización aprobada"/"pedido despachado"/
// "PQRS respondida" en cualquier página del sitio, no solo cuando ya está parado en la lista
// correspondiente - la detección de "cambió mientras estabas navegando" vive dentro de cada
// cargarX() (ver stores/cotizaciones.js, ventas.js, pqrs.js). Nunca corre para admin: ese tiene
// su propio sondeo en AdminLayout.vue con sus propios contadores.
const INTERVALO_SONDEO_MS = 60000
let intervaloSondeoCliente = null

useInactivityLogout().iniciar()

const loaderHidden = ref(false)
const backTopVisible = ref(false)

function onScroll() {
  backTopVisible.value = window.scrollY > 400
}

function scrollToTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

// Reemplaza el globo de validación nativo del navegador ("Completa este campo") por
// ValidationTooltip.vue en TODO el sitio sin tocar formulario por formulario: `invalid` no
// burbujea (hay que escucharlo en captura sobre document), y preventDefault() apaga el globo
// nativo pero el navegador sigue bloqueando el submit igual - por eso no hace falta duplicar
// la validación required/type=email/min/etc. que cada formulario ya declara en su HTML.
const { ocultarTooltip, marcarError } = useValidationTooltip()
function mensajeValidacion(el) {
  const v = el.validity
  if (v.valueMissing) return 'Completa este campo.'
  if (v.typeMismatch) return el.type === 'email' ? 'Ingresa un correo electrónico válido.' : 'El formato no es válido.'
  if (v.tooShort) return `Debe tener al menos ${el.minLength} caracteres.`
  if (v.tooLong) return `No puede tener más de ${el.maxLength} caracteres.`
  if (v.rangeUnderflow) return `El valor mínimo es ${el.min}.`
  if (v.rangeOverflow) return `El valor máximo es ${el.max}.`
  if (v.patternMismatch) return 'El formato no es válido.'
  return el.validationMessage || 'Revisa este campo.'
}
// Al enviar un formulario con varios campos inválidos, el navegador dispara `invalid` en CADA
// uno, de arriba hacia abajo, pero nativamente solo enfoca/reporta el primero - sin este freno,
// el último evento pisaría al primero y el tooltip terminaría en el último campo vacío. El freno
// se suelta con setTimeout y no con queueMicrotask: el navegador corre las microtareas entre un
// `invalid` y el siguiente, así que con microtarea se soltaba antes de tiempo y ganaba el último.
let procesandoLoteInvalido = false
function onCampoInvalido(e) {
  const el = e.target
  if (!('validity' in el)) return
  e.preventDefault()
  if (procesandoLoteInvalido) return
  procesandoLoteInvalido = true
  setTimeout(() => { procesandoLoteInvalido = false })
  // marcarError lleva el campo a la vista y mide el tooltip cuando ya terminó el scroll - medirlo
  // antes lo dejaba donde estaba el campo (fuera de pantalla si el botón de enviar está abajo).
  marcarError(el, mensajeValidacion(el))
  el.addEventListener('input', ocultarTooltip, { once: true })
}

onMounted(() => {
  setTimeout(() => (loaderHidden.value = true), 2200)
  window.addEventListener('scroll', onScroll)
  document.addEventListener('invalid', onCampoInvalido, true)
  catalog.cargarCategorias()
  catalog.cargarProductos()
  catalog.cargarInventario()
  catalog.cargarGruposVariante()
  catalog.cargarPromociones()
  catalog.cargarServicios()
  catalog.cargarImpuestos()
  catalog.cargarConfiguracion()

  if (auth.isAuthenticated && !auth.isAdmin) {
    cotizStore.cargarCotizaciones()
    ventasStore.cargarVentas()
    pqrsStore.cargarPqrs()
    intervaloSondeoCliente = setInterval(() => {
      cotizStore.cargarCotizaciones()
      ventasStore.cargarVentas()
      pqrsStore.cargarPqrs()
    }, INTERVALO_SONDEO_MS)
  }
})
onUnmounted(() => {
  window.removeEventListener('scroll', onScroll)
  document.removeEventListener('invalid', onCampoInvalido, true)
  if (intervaloSondeoCliente) clearInterval(intervaloSondeoCliente)
})
</script>

<template>
  <div id="page-loader" :class="{ hidden: loaderHidden }">
    <div class="loader-logo">ACABADOS Y DISEÑOS <span>1A</span></div>
    <div class="loader-bar"><div class="loader-bar-fill"></div></div>
  </div>

  <NavBar v-if="!route.meta.hideChrome" />
  <main :class="{ 'main-content': !route.meta.hideChrome }">
    <RouterView v-slot="{ Component }">
      <!-- Nueva Cotización guarda un borrador (productos/servicios agregados, pestaña activa) en
           memoria mientras dura la sesión — si el cliente sale a leer los Términos y vuelve, lo
           encuentra exactamente como lo dejó, en vez de perder lo que llevaba armado. -->
      <keep-alive :include="['CotizacionesView']">
        <component :is="Component" />
      </keep-alive>
    </RouterView>
  </main>
  <FooterSite v-if="!route.meta.hideChrome" />

  <template v-if="!route.meta.hideChrome">
    <a
      :href="`https://wa.me/${catalog.configuracion.whatsapp}?text=Hola!%20Quiero%20más%20información`"
      target="_blank"
      rel="noopener"
      id="whatsapp-float"
      title="Contáctanos por WhatsApp"
    >
      <i class="ri-whatsapp-line"></i>
    </a>
    <button id="back-to-top" :class="{ visible: backTopVisible }" title="Volver arriba" @click="scrollToTop">
      <i class="ri-arrow-up-line"></i>
    </button>
  </template>

  <ToastContainer />
  <SessionWarningModal />
  <CenterMessage />
  <ValidationTooltip />
</template>
